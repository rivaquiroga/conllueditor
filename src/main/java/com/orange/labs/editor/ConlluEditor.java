/* This library is under the 3-Clause BSD License

Copyright (c) 2018, Orange S.A.

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

  1. Redistributions of source code must retain the above copyright notice,
     this list of conditions and the following disclaimer.

  2. Redistributions in binary form must reproduce the above copyright notice,
     this list of conditions and the following disclaimer in the documentation
     and/or other materials provided with the distribution.

  3. Neither the name of the copyright holder nor the names of its contributors
     may be used to endorse or promote products derived from this software without
     specific prior written permission.

 THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
 THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

 @author Johannes Heinecke
 @version 1.12.0 as of 12th May 2019
 */
package com.orange.labs.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.orange.labs.conllparser.ConllException;
import com.orange.labs.conllparser.ConllFile;
import com.orange.labs.conllparser.ConllSentence;
import com.orange.labs.conllparser.ConllWord;
import com.orange.labs.httpserver.ServeurHTTP;
import java.io.BufferedReader;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

/**
 * classe très basique qui lit un fichier CONLL, affiche la premiere phrase,
 * permet de modifier les liens et sauvegarder le fichier modifié
 *
 * @author Johannes Heinecke <johannes.heinecke@orange.com>
 */
public class ConlluEditor {

    ConllFile cfile;
    File filename;
    //ConllSentence csent = null;
    //int currentSentenceId = 0;
    //ConllWord modWord = null; // last modified word
    int numberOfSentences = 0;
    int mode = 0; // 1: noedit 2: reread file at each operation (disables editing)

    Set<String> validUPOS = null;
    Set<String> validXPOS = null;
    Set<String> validDeprels = null;
    Validator validator = null;
    History history;
    boolean callgitcommit = true;
    int changesSinceSave = 0;
    int saveafter = 0; // save after n changes

    private String programmeversion;

    public enum Raw {
        LATEX, CONLLU, SDPARSE, VALIDATION
    };

    public ConlluEditor(String conllfile) throws ConllException, IOException {
        // produit avec le mvn plugin "properties-maven-plugin" (voir pom.xml)
        java.util.Properties p = new Properties();
        p.load(ClassLoader.getSystemResourceAsStream("conllueditor.properties"));
        programmeversion = p.getProperty("version");
        System.err.format("ConlluEditor V %s\n", programmeversion);
        filename = new File(conllfile);
        filename = filename.getAbsoluteFile().toPath().normalize().toFile();
        init();
        System.out.println("Number of sentences loaded: " + numberOfSentences);

        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                try {
                    Thread.sleep(200);
                    if (changesSinceSave > 0) {
                        System.err.println("Shutting down ConlluEditor...");
                        changesSinceSave = saveafter;
                        String f = writeBackup(0, null, null);
                        System.err.println("Saved " + f);
                    }

                } catch (InterruptedException e) {
                    e.printStackTrace();
                } catch (IOException ex) {
                    System.err.println("Error during shutdown: " + ex.getMessage());
                }
            }
        });
    }

    private void init() throws IOException, ConllException {
        System.err.println("Loading " + filename);
        cfile = new ConllFile(filename, null);
        numberOfSentences = cfile.getSentences().size();
    }

    public void setMode(int m) {
        mode = m;
    }

    private Set<String> readList(List<String> filenames) throws IOException {
        Set<String> valid = new HashSet<>();

        for (String fn : filenames) {
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(fn), StandardCharsets.UTF_8));

            String line;
            int ct = 0;
            while ((line = br.readLine()) != null) {
                if (!line.isEmpty() && !line.startsWith("#")) {
                    valid.add(line);
                }
            }
        }
        return valid;
    }

    public void setValidUPOS(List<String> filenames) throws IOException {
        validUPOS = readList(filenames);
        System.err.format("%d valid UPOS read from %s\n", validUPOS.size(), filenames.toString());
    }

    public void setValidXPOS(List<String> filenames) throws IOException {
        validXPOS = readList(filenames);
        System.err.format("%d valid XPOS read from %s\n", validXPOS.size(), filenames.toString());
    }

    public void setValidDeprels(List<String> filenames) throws IOException {
        validDeprels = readList(filenames);
        System.err.format("%d valid Deprel read from %s\n", validDeprels.size(), filenames.toString());
    }

    public void setValidator(String validatorconf) {
        validator = new Validator(validatorconf);
    }

    public void setSaveafter(int saveafter) {
        this.saveafter = saveafter;
        System.err.format("saving file after %d edits\n", saveafter);
    }

    private JsonObject prepare(int sentid) {
        JsonObject solution = new JsonObject();
        solution.addProperty("sentenceid", sentid); //currentSentenceId);
        solution.addProperty("maxsentence", numberOfSentences);
        return solution;
    }

    private String formatErrMsg(String msg, int sentid) {
        JsonObject solution = prepare(sentid);
        solution.addProperty("error", msg);
        return solution.toString();
    }

    private String formatSaveMsg(String msg, int sentid) {
        JsonObject solution = prepare(sentid);
        solution.addProperty("message", msg);
        solution.addProperty("changes", changesSinceSave);
        return solution.toString();
    }

    private String returnTree(int sentid, ConllSentence csent) throws ConllException {
        return returnTree(sentid, csent, null);
    }

    private String returnTree(int sentid, ConllSentence csent, ConllSentence.Highlight highlight) throws ConllException {
        csent.normalise();
        csent.makeTrees(null);

        Map<Integer, Integer> heights = calculate_flat_arcs_height(csent);
        for (Integer id : heights.keySet()) {
            ConllWord cw = csent.getWord(id);
            cw.setArc_height(heights.get(id));
        }
        JsonObject solution = prepare(sentid);
        solution.addProperty("sentence", csent.getSentence());
        solution.addProperty("length", csent.getWords().size());

        if (csent.getSentid() != null) {
            solution.addProperty("sent_id", csent.getSentid());
        }
        solution.addProperty("changes", changesSinceSave);
        //solution.addProperty("latex", csent.getLaTeX());
        ConllSentence.AnnotationErrors ae = new ConllSentence.AnnotationErrors();
        solution.add("tree", csent.toJsonTree(validUPOS, validXPOS, validDeprels, highlight, ae)); // RelationExtractor.conllSentence2Json(csent));
        solution.addProperty("info", csent.getHead().getMiscStr()); // pour les fichiers de règles, il y a de l'info dans ce chapps

        // returning number of errors
        boolean anyerrors = false;
        JsonObject errors = new JsonObject();
        int numHeads = csent.getHeads().size() - csent.numOfEmptyWords();

        if (numHeads > 1) {
            anyerrors = true;
            errors.addProperty("heads", csent.getHeads().size());
        }

        int badroots = 0;
        for (ConllWord cw : csent.getWords()) {
            if (cw.getHead() != 0 && cw.getDeplabel().equals("root")) {
                badroots++;
            }
        }
        if (badroots > 0) {
            anyerrors = true;
            errors.addProperty("badroots", badroots);
        }
        if (ae.upos > 0) {
            anyerrors = true;
            errors.addProperty("invalidUPOS", ae.upos);
        }
        if (ae.xpos > 0) {
            anyerrors = true;
            errors.addProperty("invalidXPOS", ae.xpos);
        }
        if (ae.deprel > 0) {
            anyerrors = true;
            errors.addProperty("invalidDeprels", ae.deprel);
        }
        if (anyerrors) {
            solution.add("errors", errors);
        }

        solution.addProperty("comments", csent.getCommentsStr());
        if (history != null) {
            solution.addProperty("canUndo", history.canUndo());
            solution.addProperty("canRedo", history.canRedo());
        } else {
            solution.addProperty("canUndo", false);
            solution.addProperty("canRedo", false);
        }
//        Map<Integer, Integer> heights = calculate_flat_arcs_height(csent);
//        JsonObject arc = new JsonObject();
//        for (Integer id : heights.keySet()) {
//            arc.addProperty("" + id, heights.get(id));
//        }
        //solution.add("arc_heights", arc);

        //Gson gson = new GsonBuilder().setPrettyPrinting().create();
        //System.out.println("SSSSSSSS " +solution);
        return solution.toString();
    }

    public String getInfo() {
        if (mode > 0) {
            return filename.getAbsolutePath() + " [browsing mode only]";
        }
        return filename.getAbsolutePath();
    }

    /** get raw text; Latex, conllu, sdparse or the output of the validation */
    public String getraw(Raw raw, int currentSentenceId) {
        JsonObject solution = new JsonObject();

        ConllSentence csent = null;
        if (currentSentenceId >= 0 && currentSentenceId < numberOfSentences) {
            csent = cfile.getSentences().get(currentSentenceId);
        }

        if (csent != null) {
            switch (raw) {
                case VALIDATION:
                    if (validator == null) {
                        solution.addProperty("raw", "ERROR: no validator configuration given");
                    } else {
                        try {
                            solution.addProperty("raw", validator.validate(csent));
                        } catch (InterruptedException  | IOException e) {
                            solution.addProperty("raw", "Validator error: " + e.getMessage());
                        }
                    }

                    break;
                case LATEX:
                    solution.addProperty("raw", csent.getLaTeX());
                    break;
                case SDPARSE:
                    solution.addProperty("raw", csent.getSDparse());
                    break;
                case CONLLU:
                default:
                    solution.addProperty("raw", csent.toString());
                    break;
            }
        } else {
            solution.addProperty("raw", "no sentence available");
        }
        return solution.toString();
    }

    public String getValidlists() {
        JsonObject solution = new JsonObject();
        if (validDeprels != null) {
            List<String> tmp = new ArrayList<>();
            for (String d : validDeprels) {
                tmp.add(d);
            }
            Collections.sort(tmp);

            JsonArray jd = new JsonArray();
            for (String d : tmp) {
                jd.add(d);
            }
            solution.add("validdeprels", jd);
        }
        if (validUPOS != null) {
            List<String> tmp = new ArrayList<>();
            for (String d : validUPOS) {
                tmp.add(d);
            }
            Collections.sort(tmp);
            JsonArray jd = new JsonArray();
            for (String d : tmp) {
                jd.add(d);
            }
            solution.add("validUPOS", jd);
        }

        if (validXPOS != null) {
            List<String> tmp = new ArrayList<>();
            for (String d : validXPOS) {
                tmp.add(d);
            }
            Collections.sort(tmp);
            JsonArray jd = new JsonArray();
            for (String d : tmp) {
                jd.add(d);
            }
            solution.add("validXPOS", jd);
        }

        solution.addProperty("filename", filename.getAbsolutePath());
        solution.addProperty("version", programmeversion);
        solution.addProperty("reinit", mode);
        return solution.toString();
    }

    // TODO: write and commit file only when changing sentence: problem with multithread
    public String process(String command, int currentSentenceId, String editinfo) {
        if (mode == 2) {
            try {
                init();
            } catch (IOException | ConllException ex) {
                return formatErrMsg("Cannot reinit file " + filename, currentSentenceId);
            }
        }

        System.err.println("COMMAND [" + command + "] sid: " + currentSentenceId);
        try {
            if (!command.startsWith("mod ")) {
                history = null;
            } else {
                if (mode != 0) {
                    return formatErrMsg("NO editing in browse mode", currentSentenceId);
                }
                changesSinceSave += 1;
            }

            ConllSentence csent; // = cfile.getSentences().get(currentSentenceId);

            if (command.equals("prec")) {
                if (currentSentenceId > 0) {
                    currentSentenceId--;
                }

                csent = cfile.getSentences().get(currentSentenceId);
                return returnTree(currentSentenceId, csent);
            } else if (command.equals("next")) {
                if (currentSentenceId < numberOfSentences - 1) {
                    currentSentenceId++;
                }
                csent = cfile.getSentences().get(currentSentenceId);
                return returnTree(currentSentenceId, csent);

            } else if (command.startsWith("findcomment ")) {
                String[] f = command.trim().split(" +", 3);
                if (f.length != 3) {
                    return formatErrMsg("INVALID syntax '" + command + "'", currentSentenceId);
                }

                // si le deuxième mot est "true" on cherche en arrière
                boolean backwards = f[1].equalsIgnoreCase("true");
                // si on met le mot cherché entre double quote, on peut y inclure des espaces
                String motAtrouver = f[2];
                if (motAtrouver.charAt(0) == '"' && motAtrouver.endsWith("\"")) {
                    motAtrouver = motAtrouver.substring(1, motAtrouver.length() - 1);
                    //System.err.println("<" + motAtrouver + ">");
                }
                for (int i = (backwards ? currentSentenceId - 1 : currentSentenceId + 1);
                        (backwards ? i >= 0 : i < numberOfSentences);
                        i = (backwards ? i - 1 : i + 1)) {
                    ConllSentence cs = cfile.getSentences().get(i);
                    String text = cs.getCommentsStr();
                    int wordoffset = text.indexOf(motAtrouver);

                    if (wordoffset > -1) {
                        List<Integer> startindex = new ArrayList<>(); // offset where the words start
                        int offset = 0;
                        for (ConllWord cw : cs.getWords()) {
                            startindex.add(offset);
                            offset += cw.getForm().length() + 1;
                        }

                        int firstid = -1;
                        int lastid = -1;
                        int id = 0;
                        for (int ix : startindex) {
                            //System.err.println("AAA " + wordoffset + " ix:" + ix + " " + id);
                            if (ix > wordoffset && firstid == -1) {
                                firstid = id;
                                //System.err.println("First " + firstid);
                            }
                            id++;
                            if (ix <= wordoffset + motAtrouver.length()) {
                                lastid = id;
                                //System.err.println("Last " + lastid);
                            }
                        }

                        currentSentenceId = i;

                        if (motAtrouver.charAt(0) == ' ') {
                            firstid++;
                        }
                        if (motAtrouver.endsWith(" ")) {
                            lastid--;
                        }

                        ConllSentence.Highlight hl = null; //new ConllSentence.Highlight(ConllWord.Fields.FORM, firstid, lastid);
                        return returnTree(currentSentenceId, cs, hl /*, motAtrouver*/);
                    }
                }
                return formatErrMsg("Comment not found '" + motAtrouver + "'", currentSentenceId);

            } else if (command.startsWith("findword ")) {
                String[] f = command.trim().split(" +", 3);
                if (f.length != 3) {
                    return formatErrMsg("INVALID syntax '" + command + "'", currentSentenceId);
                }

                // si le deuxième mot est "true" on cherche en arrière
                boolean backwards = f[1].equalsIgnoreCase("true");

                // si on met le mot cherché entre double quote, on peut y inclure des espaces
                String motAtrouver = f[2];
                if (motAtrouver.charAt(0) == '"' && motAtrouver.endsWith("\"")) {
                    motAtrouver = motAtrouver.substring(1, motAtrouver.length() - 1);
                    //System.err.println("<" + motAtrouver + ">");
                }
                for (int i = (backwards ? currentSentenceId - 1 : currentSentenceId + 1);
                        (backwards ? i >= 0 : i < numberOfSentences);
                        i = (backwards ? i - 1 : i + 1)) {
                    ConllSentence cs = cfile.getSentences().get(i);
                    Map<Integer, Integer>pos2id = new TreeMap<>();
                    String text = cs.getSentence(pos2id);
                    //System.err.println("zzzzz " + pos2id);
                    int wordoffset = text.indexOf(motAtrouver);

                    if (wordoffset > -1) {
                        int wordendoffset = wordoffset + motAtrouver.length();
                         if (motAtrouver.charAt(0) == ' ') {
                            wordoffset++;
                        }
                        if (motAtrouver.endsWith(" ")) {
                            wordendoffset--;
                        }
                        //System.err.println("eeee " + motAtrouver + " "+ wordoffset + " " + wordendoffset);
                        int firstid = -1;
                        int lastid = -1;


                        for(Integer cwstartpos : pos2id.keySet()) {
                            if (cwstartpos <= wordoffset) {
                                firstid = pos2id.get(cwstartpos);
                            }
                            if (cwstartpos < wordendoffset) {
                                lastid = pos2id.get(cwstartpos);
                            }
                        }

                        //System.err.println("rrrrr " + firstid + " " + lastid);
                        currentSentenceId = i;

                        ConllSentence.Highlight hl = new ConllSentence.Highlight(ConllWord.Fields.FORM, firstid, lastid);
                        return returnTree(currentSentenceId, cs, hl /*, motAtrouver*/);
                    }
                }
                return formatErrMsg("Word not found '" + motAtrouver + "'", currentSentenceId);

            } else if (command.startsWith("findmulti ")) {
                String[] f = command.trim().split(" +");
                if (f.length != 3) {
                    return formatErrMsg("INVALID syntax '" + command + "'", currentSentenceId);
                }

                // si le deuxième mot est "true" on cherche en arrière
                boolean backwards = f[1].equalsIgnoreCase("true");
                String[] elems = f[2].split("/");

                class SearchField {
                    ConllWord.Fields field;
                    String value;

                    public SearchField(String f) {
                        String [] tmp = f.split(":", 2);
                        value = tmp[1];

                        if (tmp[0].startsWith("l")) {
                            field = ConllWord.Fields.LEMMA;
                        } else if (tmp[0].startsWith("u")) {
                            field = ConllWord.Fields.UPOS;
                        } else if (tmp[0].startsWith("x")) {
                            field = ConllWord.Fields.XPOS;
                        } else if (tmp[0].startsWith("d")) {
                            field = ConllWord.Fields.DEPREL;
                        } else /*if (f[0].equals("findxpos")) */ {
                            field = ConllWord.Fields.FORM;
                        }
                    }
                }

                List<SearchField>fields = new ArrayList<>();
                for (String e: elems) {
                    if (!e.contains(":")) {
                        return formatErrMsg("INVALID syntax '" + command + "'", currentSentenceId);
                    }
                    fields.add(new SearchField(e));
                }

                for (int i = (backwards ? currentSentenceId - 1 : currentSentenceId + 1);
                        (backwards ? i >= 0 : i < numberOfSentences);
                        i = (backwards ? i - 1 : i + 1)) {
                    ConllSentence cs = cfile.getSentences().get(i);

                    Iterator<ConllWord> cwit = cs.getWords().iterator();
                    while (cwit.hasNext()) {
                        ConllWord cw = cwit.next();
                        if (cw.matchesField(fields.get(0).field, fields.get(0).value)) {
                            currentSentenceId = i;
                            int firstid = cw.getId();
                            boolean ok = true;
                            for (int j = 1; j < elems.length; ++j) {
                                if (!cwit.hasNext()) {
                                    ok = false;
                                    break;
                                }
                                cw = cwit.next();
                                if (!cw.matchesField(fields.get(j).field, fields.get(j).value)) {
                                    ok = false;
                                    break;
                                }
                            }
                            if (ok) {
                                ConllSentence.Highlight hl = new ConllSentence.Highlight(ConllWord.Fields.LEMMA, firstid, cw.getId());
                                return returnTree(currentSentenceId, cs, hl); //cw.getUpostag());
                            }
                        }
                    }
                }
                return formatErrMsg("not found '" + f[2] + "'", currentSentenceId);

            } else if (command.startsWith("findlemma ")
                    || command.startsWith("findupos ")
                    || command.startsWith("findxpos ")) {
                String[] f = command.trim().split(" +");
                if (f.length != 3) {
                    return formatErrMsg("INVALID syntax '" + command + "'", currentSentenceId);
                }
                ConllWord.Fields field;
                if (f[0].equals("findlemma")) {
                    field = ConllWord.Fields.LEMMA;
                } else if (f[0].equals("findupos")) {
                    field = ConllWord.Fields.UPOS;
                } else /*if (f[0].equals("findxpos")) */ {
                    field = ConllWord.Fields.XPOS;
                }

                // si le deuxième mot est "true" on cherche en arrière
                boolean backwards = f[1].equalsIgnoreCase("true");

                String[] elems = f[2].split("/");
                for (int i = (backwards ? currentSentenceId - 1 : currentSentenceId + 1);
                        (backwards ? i >= 0 : i < numberOfSentences);
                        i = (backwards ? i - 1 : i + 1)) {
                    ConllSentence cs = cfile.getSentences().get(i);

                    Iterator<ConllWord> cwit = cs.getWords().iterator();
                    while (cwit.hasNext()) {
                        ConllWord cw = cwit.next();
                        if (cw.matchesField(field, elems[0])) {
                            currentSentenceId = i;
                            int firstid = cw.getId();
                            boolean ok = true;
                            for (int j = 1; j < elems.length; ++j) {
                                if (!cwit.hasNext()) {
                                    ok = false;
                                    break;
                                }
                                cw = cwit.next();
                                if (!cw.matchesField(field, elems[j])) {
                                    ok = false;
                                    break;
                                }
                            }
                            if (ok) {
                                ConllSentence.Highlight hl = new ConllSentence.Highlight(field, firstid, cw.getId());
                                return returnTree(currentSentenceId, cs, hl); //cw.getUpostag());
                            }
                        }
                    }
                }
                return formatErrMsg(field + " not found '" + f[2] + "'", currentSentenceId);

            } else if (command.startsWith("finddeprel ")) {
                String[] f = command.trim().split(" +");
                if (f.length != 3) {
                    return formatErrMsg("INVALID syntax '" + command + "'", currentSentenceId);
                }

                String[] rels = f[2].split("[<>]");
                String[] updown = f[2].split("[^<>]+");
                //System.err.println("RL " + String.join(",", rels));
                //System.err.println("UD " + String.join(",", updown));
                // si le deuxième mot est "true" on cherche en arrière
                boolean backwards = f[1].equalsIgnoreCase("true");

                for (int i = (backwards ? currentSentenceId - 1 : currentSentenceId + 1);
                        (backwards ? i >= 0 : i < numberOfSentences);
                        i = (backwards ? i - 1 : i + 1)) {

                    ConllSentence cs = cfile.getSentences().get(i);
                    for (ConllWord cw : cs.getWords()) {
                        if (cw.matchesDeplabel(rels[0])) {
                            if (rels.length == 1) {
                                currentSentenceId = i;
                                //csent = cs;
                                ConllSentence.Highlight hl = new ConllSentence.Highlight(ConllWord.Fields.DEPREL, cw.getId());
                                return returnTree(currentSentenceId, cs, hl); //cw.getDeplabel());
                            } else {
                                // chaine de deprels
                                cs.makeTrees(null);

                                boolean ok = true;
                                ConllWord head = cw;

                                for (int r = 1; r < rels.length; ++r) {
                                    if (updown[r].equals(">")) {
                                        // does head has deprel
                                        head = head.getHeadWord();
                                        if (head == null || !head.matchesDeplabel(rels[r])) {
                                            ok = false;
                                            break;
                                        }
                                    } else {
                                        // does word have dependent with deprel
                                        head = head.getHeadWord();
                                        List<ConllWord> deps = head.getDWordsRE(rels[r]);
                                        if (deps == null || deps.isEmpty()) {
                                            ok = false;
                                            break;
                                        }
                                    }
                                }
                                if (ok) {
                                    currentSentenceId = i;
                                    return returnTree(currentSentenceId, cs);
                                }
                            }
                        }
                    }
                }
                return formatErrMsg("DepRel not found " + f[2], currentSentenceId);

            } else if (command.startsWith("read ")) {
                String[] f = command.trim().split(" +");
                if (f.length != 2) {
                    return formatErrMsg("INVALID command '" + command + "'", currentSentenceId);

                }
                int sn;
                if (f[1].equals("last")) {
                    sn = cfile.getSentences().size() - 1;
                } else {
                    sn = Integer.parseInt(f[1]);
                }
                if (sn < 0 && sn >= cfile.getSentences().size()) {
                    return formatErrMsg("NO sentence number '" + command + "'", currentSentenceId);

                }
                if (sn >= 0 && sn < cfile.getSentences().size()) {
                    csent = cfile.getSentences().get(sn);
                    currentSentenceId = sn;
                    return returnTree(currentSentenceId, csent);
                } else {
                    return formatErrMsg("INVALID sentence number '" + command + "'", currentSentenceId);
                }

            } else if (command.startsWith("mod upos ")
                    || command.startsWith("mod xpos ")
                    || command.startsWith("mod lemma ")
                    || command.startsWith("mod form ")
                    || command.startsWith("mod enhdeps ")
                    || command.startsWith("mod feat ")
                    || command.startsWith("mod misc ")) {
                // on attend
                //    "mod upos id newupos" par ex. mod xpos 3 ART"
                String[] f = command.trim().split(" +", 4);
                if (f.length < 4) {
                    return formatErrMsg("INVALID command length '" + command + "'", currentSentenceId);
                }
                ConllWord modWord = null;
                try {
                    csent = cfile.getSentences().get(currentSentenceId);
                    if (f[2].contains(".")) {
                        /*String[] id = f[2].split("\\.");
                        List<ConllWord> ews = csent.getEmptyWords().get(Integer.decode(id[0]));
                        int subid = Integer.parseInt(id[1]);
                        if (subid > ews.size()) {
                            return formatErrMsg("INVALID subid '" + command + "'", currentSentenceId);
                        }
                        modWord = ews.get(subid - 1);*/
                        modWord = csent.getEmptyWord(f[2]);
                        if (modWord == null)
                             return formatErrMsg("INVALID id '" + command + "'", currentSentenceId);
                    } else {
                        int id = Integer.parseInt(f[2]);

                        if (id < 1 || id > csent.getWords().size()) {
                            return formatErrMsg("INVALID id '" + command + "'", currentSentenceId);
                        }
                        modWord = csent.getWords().get(id - 1);
                    }
                } catch (NumberFormatException e) {
                    return formatErrMsg("INVALID id (not an integer) '" + command + "' " + e.getMessage(), currentSentenceId);
                }

                if (history == null) {
                    history = new History(200);
                }
                history.add(csent);

                //ConllWord modWord = csent.getWords().get(id - 1);
                switch (f[1]) {
                    case "upos":
                        modWord.setUpostag(f[3]);
                        break;
                    case "xpos":
                        modWord.setXpostag(f[3]);
                        break;
                    case "form":
                        modWord.setForm(f[3]);
                        break;
                    case "lemma":
                        modWord.setLemma(f[3]);
                        break;
                    case "feat": {
                        modWord.setFeatures(f[3]);
                        break;
                    }
                    case "misc": {
                        modWord.setMisc(f[3]);
                        break;
                    }
                    case "enhdeps": {
                        modWord.setDeps(f[3]);
                        break;
                    }

                }

                try {
                    writeBackup(currentSentenceId, modWord, editinfo);
                } catch (IOException ex) {
                    return formatErrMsg("Cannot save file: " + ex.getMessage(), currentSentenceId);
                }
                return returnTree(currentSentenceId, csent);

            } else if (command.startsWith("mod compose")) {
                // add composed form: mod compose <id> <composelength>
                String[] f = command.trim().split(" +");
                if (f.length < 4) {
                    return formatErrMsg("INVALID command length '" + command + "'", currentSentenceId);
                }

                int id;
                int complen;
                try {
                    id = Integer.parseInt(f[2]);
                    csent = cfile.getSentences().get(currentSentenceId);
                    if (id < 1 || id > csent.getWords().size()) {
                        return formatErrMsg("INVALID id '" + command + "'", currentSentenceId);
                    }
                    complen = Integer.parseInt(f[3]);
                    if (id + complen > csent.getWords().size()) {
                        return formatErrMsg("INVALID complen (to big) '" + command + "'", currentSentenceId);
                    }
                } catch (NumberFormatException e) {
                    return formatErrMsg("INVALID id (not an integer) '" + command + "' " + e.getMessage(), currentSentenceId);
                }
                //System.err.println("<" + f[1] + ">");

                if (history == null) {
                    history = new History(200);
                }
                history.add(csent);

                ConllWord composedWord = new ConllWord(csent.getWords().get(id - 1).getForm(), id, id+complen-1);
                csent.addWord(composedWord, id);
                return returnTree(currentSentenceId, csent);

            } else if (command.startsWith("mod editmwe")) { // mod editmwe start end form
                String[] f = command.trim().split(" +");
                if (f.length < 5) {
                    return formatErrMsg("INVALID command length '" + command + "'", currentSentenceId);
                }

                int start;
                int end;
                String form = f[4];

                try {
                    start = Integer.parseInt(f[2]);
                    csent = cfile.getSentences().get(currentSentenceId);
                    if (start < 1 || start > csent.getWords().size()) {
                        return formatErrMsg("INVALID id '" + command + "'", currentSentenceId);
                    }
                    end = Integer.parseInt(f[3]);

                    if ((end > 0 && end < start) || end > csent.getWords().size()) {
                        return formatErrMsg("INVALID id '" + command + "'", currentSentenceId);
                    }

                } catch (NumberFormatException e) {
                    return formatErrMsg("INVALID id (not an integer) '" + command + "' " + e.getMessage(), currentSentenceId);
                }
                //System.err.println("<" + f[1] + ">");

                if (history == null) {
                    history = new History(200);
                }
                history.add(csent);

                // delete MW token

                if (end == 0) {
                    csent.deleteContracted(start);
                    return returnTree(currentSentenceId, csent);
                }
                // modify it
                ConllWord cw = csent.getContracted(start);
                if (cw != null) {
                    cw.setForm(form);
                    cw.setSubId(end);
                    cw.setId(start);
                }
                return returnTree(currentSentenceId, csent);

            } else if (command.startsWith("mod split ")
                    || command.startsWith("mod join ")) {
                String[] f = command.trim().split(" +");

                if (f.length < 3) {
                    return formatErrMsg("INVALID command length '" + command + "'", currentSentenceId);
                }
                int id;
                try {
                    id = Integer.parseInt(f[2]);
                    csent = cfile.getSentences().get(currentSentenceId);
                    if (id < 1 || id > csent.getWords().size()) {
                        return formatErrMsg("INVALID id '" + command + "'", currentSentenceId);
                    }
                } catch (NumberFormatException e) {
                    return formatErrMsg("INVALID id (not an integer) '" + command + "' " + e.getMessage(), currentSentenceId);
                }
                //System.err.println("<" + f[1] + ">");

                if (history == null) {
                    history = new History(200);
                }
                history.add(csent);

                ConllWord modWord = null;
                if (f[1].equals("split")) {
                    //System.err.println("SPLIT");
                    int splitpos = -1;
                    if (f.length > 3) {
                        try {
                             splitpos = Integer.parseInt(f[3]);
                         } catch (NumberFormatException e) {
                             return formatErrMsg("INVALID splitpos (not an integer) '" + command + "' " + e.getMessage(), currentSentenceId);
                         }
                    }

                    ConllWord curWord = csent.getWords().get(id - 1);
                    modWord = new ConllWord(curWord);

                    if (splitpos > 0 && splitpos < curWord.getForm().length()-1) {
                        curWord.setForm(curWord.getForm().substring(0, splitpos));
                        modWord.setForm(modWord.getForm().substring(splitpos));
                        if (splitpos < curWord.getLemma().length()-1) {
                            curWord.setLemma(curWord.getLemma().substring(0, splitpos));
                            modWord.setLemma(modWord.getLemma().substring(splitpos));
                        }
                    }

                    csent.addWord(modWord, id);

                } else if (f[1].equals("join")) {
                    // System.err.println("JOIN");
                    if (id >= csent.getWords().size()) {
                        return formatErrMsg("Cannot join last word '" + command + "'", currentSentenceId);
                    }
                    modWord = csent.getWords().get(id - 1);
                    csent.joinWords(id);
                }

                try {
                    writeBackup(currentSentenceId, modWord, editinfo);
                } catch (IOException ex) {
                    return formatErrMsg("Cannot save file: " + ex.getMessage(), currentSentenceId);
                }

                return returnTree(currentSentenceId, csent);

            } else if (command.startsWith("mod sentsplit ")) {
                  String[] f = command.trim().split(" +");

                if (f.length < 3) {
                    return formatErrMsg("INVALID command length '" + command + "'", currentSentenceId);
                }
                int id;
                try {
                    id = Integer.parseInt(f[2]);
                    csent = cfile.getSentences().get(currentSentenceId);
                    if (id < 1 || id > csent.getWords().size()) {
                        return formatErrMsg("INVALID id '" + command + "'", currentSentenceId);
                    }
                } catch (NumberFormatException e) {
                    return formatErrMsg("INVALID id (not an integer) '" + command + "' " + e.getMessage(), currentSentenceId);
                }
                //System.err.println("<" + f[1] + ">");

                if (history == null) {
                    history = new History(200);
                }
                history.add(csent);
                // split current sentence at word id
                ConllSentence newsent = csent.splitSentence(id);
//                System.out.println(csent);
//                System.out.println(newsent);

                cfile.getSentences().add(currentSentenceId+1, newsent);
                numberOfSentences++;
                try {
                    writeBackup(currentSentenceId, null, editinfo);
                } catch (IOException ex) {
                    return formatErrMsg("Cannot save file: " + ex.getMessage(), currentSentenceId);
                }
                return returnTree(currentSentenceId, csent);

            } else if (command.startsWith("mod sentjoin")) {
                String[] f = command.trim().split(" +");
                csent = cfile.getSentences().get(currentSentenceId);

//                if (history == null) {
//                    history = new History(200);
//                }
//                history.add(csent);
                if (currentSentenceId >= cfile.getSentences().size()) {
                     return formatErrMsg("No next sentence to join", currentSentenceId);
                }
                ConllSentence nextsent = cfile.getSentences().get(currentSentenceId+1);
                csent.joinsentence(nextsent);
                cfile.getSentences().remove(currentSentenceId+1);
                numberOfSentences--;
                try {
                    writeBackup(currentSentenceId, null, editinfo);
                } catch (IOException ex) {
                    return formatErrMsg("Cannot save file: " + ex.getMessage(), currentSentenceId);
                }
                return returnTree(currentSentenceId, csent);

            } else if (command.startsWith("mod insert ")) {
                String[] f = command.trim().split(" +");
                if (f.length < 4) {
                    return formatErrMsg("INVALID command length '" + command + "'", currentSentenceId);
                }
                int id;
                try {
                    id = Integer.parseInt(f[2]);
                    csent = cfile.getSentences().get(currentSentenceId);
                    if (id < 1 || id > csent.getWords().size()) {
                        return formatErrMsg("INVALID id '" + command + "'", currentSentenceId);
                    }
                } catch (NumberFormatException e) {
                    return formatErrMsg("INVALID id (not an integer) '" + command + "' " + e.getMessage(), currentSentenceId);
                }
                //System.err.println("<" + f[1] + ">");

                if (history == null) {
                    history = new History(200);
                }
                history.add(csent);
                String form = f[3];
                ConllWord newword = new ConllWord(form);
                if (f.length > 4) {
                    newword.setLemma(f[4]);
                    if (f.length > 5) {
                        newword.setUpostag(f[5]);
                        if (f.length > 6) {
                            newword.setXpostag(f[6]);
                        }
                    }
                }
                csent.addWord(newword, id);
                try {
                    writeBackup(currentSentenceId, newword, editinfo);
                } catch (IOException ex) {
                    return formatErrMsg("Cannot save file: " + ex.getMessage(), currentSentenceId);
                }
                return returnTree(currentSentenceId, csent);

            } else if (command.startsWith("mod comments ")) {
                String[] f = command.trim().split(" +", 3);
                if (f.length < 3) {
                    return formatErrMsg("INVALID command length '" + command + "'", currentSentenceId);
                }

                if (history == null) {
                    history = new History(200);
                }
                csent = cfile.getSentences().get(currentSentenceId);
                history.add(csent);

                ConllWord modWord = csent.getHead();
                csent.setComments(f[2]);

                try {
                    writeBackup(currentSentenceId, modWord, editinfo);
                } catch (IOException ex) {
                    return formatErrMsg("Cannot save file: " + ex.getMessage(), currentSentenceId);
                }
                return returnTree(currentSentenceId, csent);

            } else if (command.startsWith("mod undo")) {
                if (history != null) {
                    csent = cfile.getSentences().get(currentSentenceId);
                    ConllSentence cs = history.undo(csent);
                    if (cs != null) {
                        cfile.getSentences().set(currentSentenceId, cs);
                        csent = cfile.getSentences().get(currentSentenceId);
                        writeBackup(currentSentenceId, null, "undo");
                        return returnTree(currentSentenceId, csent);
                    }
                }
                return formatErrMsg("No more undo possible", currentSentenceId);

            } else if (command.startsWith("mod redo")) {
                if (history != null) {
                    ConllSentence cs = history.redo();
                    if (cs != null) {
                        cfile.getSentences().set(currentSentenceId, cs);
                        csent = cfile.getSentences().get(currentSentenceId);
                        writeBackup(currentSentenceId, null, "redo");
                        return returnTree(currentSentenceId, csent);
                    }
                }
                return formatErrMsg("No more redo possible", currentSentenceId);

            } else if (command.startsWith("mod ed")) {
                // enhanced deps
                // we attend 
                //        "mod ed add <dep> <head> nsubj"
                //        "mod ed del <dep> <head>"
                String[] f = command.trim().split(" +");

                if (f.length < 5) {
                    return formatErrMsg("INVALID command length '" + command + "'", currentSentenceId);
                }
                
                if (f[2].equals("add") && f.length < 6) {
                    return formatErrMsg("INVALID command length '" + command + "'", currentSentenceId);
                }
             
                csent = cfile.getSentences().get(currentSentenceId);
                if (history == null) {
                    history = new History(200);
                }
                history.add(csent);

                ConllWord dep = csent.getWord(f[3]);
                if (dep == null) formatErrMsg("INVALID dep id '" + command + "'", currentSentenceId);
                ConllWord head = csent.getWord(f[4]);
                if (head == null) formatErrMsg("INVALID head id '" + command + "'", currentSentenceId);
             
                if ("add".equals(f[2])) {
                    // before we add a new enhanced dep, we delete a potentially
                    // existing enhn.deprel to the same head
                    /*boolean rtc = */dep.delDeps(head.getFullId());
                    dep.addDeps(head.getFullId(), f[5]);
                    csent.setHasEnhancedDeps(true);
                }
                else if ("del".equals(f[2])) {
                    boolean rtc = dep.delDeps(f[4]);
                    if (!rtc) return formatErrMsg("ED does not exist '" + command + "'", currentSentenceId);
                }
                else {
                    return formatErrMsg("INVALID ed command '" + command + "'", currentSentenceId);
                }
                try {
                    writeBackup(currentSentenceId, dep, editinfo);
                } catch (IOException ex) {
                    return formatErrMsg("Cannot save file: " + ex.getMessage(), currentSentenceId);
                }

                return returnTree(currentSentenceId, csent);
                
                
            } else if (command.startsWith("mod ")) {
                // we expect
                //    "mod id newheadid [newdeprel]", par ex "mod 3 6 nsubj"
                //if (mode != 0) {
                //    return formatErrMsg("NO editing in browse mode", currentSentenceId);
                //}
                // mod dep newhead [deprel]
                String[] f = command.trim().split(" +");

                if (f.length < 3) {
                    return formatErrMsg("INVALID command length '" + command + "'", currentSentenceId);

                }
                int dep_id;
                int newhead_id;

                if (f[1].contains(".") || f[2].contains(".")) {
                    return formatErrMsg("empty nodes cannot be head/dependant in basic dependencies '" + command + "'", currentSentenceId);
                }
                
                try {
                    dep_id = Integer.parseInt(f[1]);
                    csent = cfile.getSentences().get(currentSentenceId);
                    if (dep_id < 1 || dep_id > csent.getWords().size()) {
                        return formatErrMsg("INVALID dep id '" + command + "'", currentSentenceId);
                    }

                    newhead_id = Integer.parseInt(f[2]);
                    if (newhead_id < 0 || newhead_id > csent.getWords().size()) {
                        return formatErrMsg("INVALID new head id '" + command + "'", currentSentenceId);

                    }
                } catch (NumberFormatException e) {
                    return formatErrMsg("INVALID id (not an integer) '" + command + "' " + e.getMessage(), currentSentenceId);
                }

                if (newhead_id == dep_id) {
                    return formatErrMsg("INVALID new head id '" + command + "'", currentSentenceId);
                }

                String newdeprel = null;
                if (f.length == 4) {
                    newdeprel = f[3];
                }

                if (history == null) {
                    history = new History(200);
                }
                history.add(csent);
                ConllWord depword = csent.getWords().get(dep_id - 1);
                ConllWord oldhead = depword.getHeadWord();
                if (depword.getHead() == newhead_id) {
                    if (newdeprel == null) {
                        //return formatErrMsg("new head == old head");
                        return returnTree(currentSentenceId, csent);
                    } else if (newdeprel.equals(depword.getDeplabel())) {
                        return formatErrMsg("new head == old head, identical deprel", currentSentenceId);
                    }
                }

                ConllWord newhead = null;
                if (newhead_id > 0) {
                    newhead = csent.getWords().get(newhead_id - 1); // 0 : nouvelle tête de la phrase
                    // check whether newhead n'est pas qq part au-dessous du mot courant
                    if (depword.commands(newhead)) {
                        return formatErrMsg("cannot make " + newhead_id + " head of " + dep_id, currentSentenceId);
                    }
                }
                depword.setHeadWord(newhead);
                depword.setHead(newhead_id);

                if (oldhead != null) {
                    oldhead.getDependentsMap().remove(dep_id);
                }
                if (newhead != null) {
                    newhead.getDependentsMap().put(dep_id, depword);
                }
                if (newdeprel != null) {
                    depword.setDeplabel(newdeprel);
                }

                ConllWord modWord = depword;
                try {
                    writeBackup(currentSentenceId, modWord, editinfo);
                } catch (IOException ex) {
                    return formatErrMsg("Cannot save file: " + ex.getMessage(), currentSentenceId);
                }

                return returnTree(currentSentenceId, csent);

            } else if (command.startsWith("save")) {
                if (mode != 0) {
                    return formatErrMsg("NO editing in browse mode", currentSentenceId);
                }
                if (changesSinceSave != 0) {
                    try {
                        changesSinceSave = saveafter;
                        String f = writeBackup(currentSentenceId, null, editinfo);
                        return formatSaveMsg("saved '" + f + "'", currentSentenceId);

                        //return returnTree(currentSentenceId, csent);
                    } catch (IOException ex) {
                        return formatErrMsg("Cannot save file: " + ex.getMessage(), currentSentenceId);
                    }
                } else {
                    return formatErrMsg("no changes to be saved", currentSentenceId);
                }
            } else {
                return formatErrMsg("invalid command '" + command + "'", currentSentenceId);
            }
        } catch (ConllException e) {
            return formatErrMsg("CoNLL error: " + e.getMessage(), currentSentenceId);
        } catch (Exception e) {
            e.printStackTrace();
            return formatErrMsg("CoNLL error: " + e.getMessage(), currentSentenceId);
        }
    }

    /**
     * calculate the height if each arc, by taking into account all short arcs
     * below. Does not take into account non-projective arcs, on ly arcs from n
     * to m
     *
     * @param cs
     */
    private Map<Integer, Integer> calculate_flat_arcs_height(ConllSentence cs) {
        // min heights of deps
        Map<Integer, Integer> minheightsLeft = new HashMap<>(); // head: highest arc to the left
        Map<Integer, Integer> minheightsRight = new HashMap<>(); // head: highest arc to the right
        // height for my arc
        Map<Integer, Integer> height = new HashMap<>(); // position: height

//        System.err.println("A maxdist " + cs.getMaxdist());
        for (int d = 1; d <= cs.getMaxdist(); ++d) {
//            System.err.println("D: " + d);
//            System.err.println(" LEFT  " + minheightsLeft);
//            System.err.println(" RIGHT " + minheightsRight);
//            System.err.println(" H     " + height);
            for (ConllWord cw : cs.getWords()) {
                if (cw.getHead() <= 0) {
                    continue;
                }

                int dist = cw.getId() - cw.getHead(); // negative: head is right
                if (Math.abs(dist) != d) {
                    continue;
                }
//                System.err.println(" cw: " + cw.getId() + " dist " + dist);
                if (dist < 0) {
                    // head is right, so we look at deps following (left of) the head
                    Integer mh = minheightsLeft.getOrDefault(cw.getHead(), 0);
                    Integer m = height.getOrDefault(cw.getId(), 0);
//                    System.err.println("  minh l for cw " + mh + " " + m);

                    int h = Math.min(Math.max(mh + 1, m + 1), d);

//                    System.err.println("   l id:" + cw.getId() + " h:" + cw.getHead());
//                    System.err.println("   lheight " + h);
                    for (int i = cw.getId(); i < cw.getHead(); ++i) {
                        h = Math.max(h, height.getOrDefault(i, 0) + 1);
//                        System.err.println("  li:" + i + " " + h);
                    }
//                    System.err.println("  height for cw " + h);
                    height.put(cw.getId(), h);
                    minheightsLeft.put(cw.getHead(), h);

                } else {
                    Integer mh = minheightsRight.getOrDefault(cw.getHead(), 0);
                    Integer m = height.getOrDefault(cw.getId(), 0);
//                    System.err.println("  rminh for cw " + mh + " " + m);

                    int h = Math.min(Math.max(mh + 1, m + 1), d);

//                    System.err.println("   r id:" + cw.getId() + " h:" + cw.getHead());
//                    System.err.println("   rheight " + h);
                    for (int i = cw.getId(); i > cw.getHead(); --i) {
                        h = Math.max(h, height.getOrDefault(i, 0) + 1);
//                        System.err.println("  ri:" + i + " " + h);
                    }

                    height.put(cw.getId(), h);
//                    System.err.println("  rheight for cw " + h);
                    minheightsRight.put(cw.getHead(), h);
                }
            }
        }
//        System.err.println("LEFT  " + minheightsLeft);
//        System.err.println("RIGHT " + minheightsRight);
//        System.err.println("H     " + height);
        return height;
    }

    /**
     * only needed for test, to avoid committing the test file
     */
    public void setCallcitcommot(boolean b) {
        callgitcommit = b;
    }

    private String suffix = ".2";

    public void setBacksuffix(String s) {
        suffix = s;
    }

    private synchronized String writeBackup(int currentSentenceId, ConllWord modWord, String editinfo) throws IOException {
        if (changesSinceSave < saveafter) {
            return null; // no need to save yet
        }
        File dir = filename.getParentFile().toPath().normalize().toFile();

        try {
            //Git git = Git.open(dir);
            FileRepositoryBuilder repositoryBuilder = new FileRepositoryBuilder();
            repositoryBuilder.findGitDir(dir);
            File gitdir = repositoryBuilder.getGitDir();
            //System.err.println("EEEEEE " + gitdir + " eeee " + dir );
            if (gitdir != null) {
                Git git = Git.open(gitdir);
                Status status = git.status().call();

                // calculer le nom du fichier par rapport du réperoitre .git
                Path gitdirbase = gitdir.getAbsoluteFile().getParentFile().toPath().normalize();
                //System.err.println("gitdirbase    " + gitdirbase);
                //System.err.println("filename      " + filename.toPath().normalize());
                Path filepathInGit = gitdirbase.relativize(filename.toPath().normalize());
                //System.err.println("IGNORED " + status.getIgnoredNotInIndex());
                //System.err.println("filenameInGit " + filepathInGit);
                Set<String> untracked = status.getUntracked();
                //System.err.println("UNTRACKED " + untracked);
//                boolean ignore = false;
//                for (String pattern : status.getIgnoredNotInIndex()) {
//
//                }
                if (!callgitcommit || untracked.contains(filepathInGit.toString())) {
                    String backUpFilename = filename + suffix;
                    //System.err.println("Write ddddd " );
                    BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(backUpFilename), StandardCharsets.UTF_8));
                    bw.write(cfile.toString());
                    bw.close();
                    System.err.printf("File '%s' not tracked by git\n", backUpFilename);
                    changesSinceSave = 0;
                    return backUpFilename;
                    //} else if (false) {
                    // git.tag().setName(tagname).setMessage(tagmessage).call();
                    //System.err.format("Tag '%s': '%s' set.", tagname, tagmessage);
                    //return tagname;
                } else {
                    BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(filename), StandardCharsets.UTF_8));
                    bw.write(cfile.toString());
                    bw.close();
                    changesSinceSave = 0;
                    git.add().addFilepattern(filepathInGit.toString()).call();
                    if (modWord == null) {
                        git.commit().setMessage(String.format("saving %s (%s)", filename, editinfo)).call();
                    } else {
                        //String sentid = "";
                        //if ()
                        git.commit().setMessage(String.format("modification: %s sentence %d, word: %d (%s)", filename, currentSentenceId + 1, modWord.getId(), editinfo)).call();
                    }
                    System.err.printf("File '%s' committed\n", filepathInGit);
                    return filename.toString();

                }
            } else {
                String backUpFilename = filename + ".2";
                //System.err.println("Write ddddd " );
                BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(backUpFilename), StandardCharsets.UTF_8));
                bw.write(cfile.toString());
                bw.close();
                changesSinceSave = 0;
                System.err.printf("Directory '%s' is not a git repository\n", dir);
                return backUpFilename;
            }
        } catch (GitAPIException ex) {
            System.err.println("GIT ERROR: " + ex.getMessage());
            // } catch (Exception ex) {
            //    ex.printStackTrace();
            System.err.printf("Directory '%s' is not a git repository\n", dir);
        }
        return null;
    }

    static void help() {
        System.err.println("usage: edit.sh [options] conllfile port");
        System.err.println("   --UPOS <files>       comma separated list of files with valid UPOS");
        System.err.println("   --XPOS <files>       comma separated list of files with valid UPOS");
        System.err.println("   --deprels <file>     comma separated list of files with valid deprels");
        System.err.println("   --validator <file>   file with validator configuration");
        System.err.println("   --rootdir <dir>      root of fileserver (must include index.html and edit.js etc.  for ConlluEditor");
        System.err.println("   --saveAfter <number> saves edited file after n changes (default save (commit) after each modification");
        System.err.println("   --verb <int>         specifiy verbosity (hexnumber, interpreted as bitmap)");
        System.err.println("   --noedit             only browsing");
        System.err.println("   --reinit             only browsing, reload file after each sentence (to read changes if the file is changed by other means)");
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            // reinit will reread the whole file at each action (to take into account external modification)
            // as a consequence, editing is not possible
            help();
            System.exit(1);
        }

        List<String> uposfiles = null;
        List<String> xposfiles = null;
        List<String> deprelfiles = null;
        String rootdir = null;
        String validator = null;
        int debug = 3;
        int saveafter = 0;
        int mode = 0; // noedit: 1, reinit: 2

        int argindex = 0;
        for (int a = 0; a < args.length - 1; ++a) {
            if (args[a].equals("--UPOS")) {
                String[] fns = args[++a].split(",");
                uposfiles = Arrays.asList(fns);
                argindex += 2;
            } else if (args[a].equals("--XPOS")) {
                String[] fns = args[++a].split(",");
                xposfiles = Arrays.asList(fns);
                argindex += 2;
            } else if (args[a].equals("--deprels")) {
                String[] fns = args[++a].split(",");
                deprelfiles = Arrays.asList(fns);
                argindex += 2;
            } else if (args[a].equals("--validator")) {
                validator = args[++a];
                argindex += 2;
            } else if (args[a].equals("--rootdir")) {
                rootdir = args[++a];
                argindex += 2;
            } else if (args[a].equals("--saveAfter")) {
                saveafter = Integer.parseInt(args[++a]);
                argindex += 2;
            } else if (args[a].equals("--verb")) {
                debug = Integer.parseInt(args[++a], 16);
                argindex += 2;
            } else if (args[a].equals("--noedit")) {
                if (mode == 0) {
                    mode = 1;
                }
                argindex += 1;
            } else if (args[a].equals("--reinit")) {
                mode = 2;
                argindex += 1;
            } else if (args[a].startsWith("-")) {
                System.err.println("Invalid option " + args[a]);
                help();
                System.exit(2);
            }
        }

        try {
            ConlluEditor ce = new ConlluEditor(args[argindex]);
            if (uposfiles != null) {
                ce.setValidUPOS(uposfiles);
            }
            if (xposfiles != null) {
                ce.setValidXPOS(xposfiles);
            }
            if (deprelfiles != null) {
                ce.setValidDeprels(deprelfiles);
            }
            if (validator != null) {
                ce.setValidator(validator);
            }
            if (saveafter != 0) {
                ce.setSaveafter(saveafter);
            }

            if (args.length > argindex + 1) {
                ServeurHTTP sh = new ServeurHTTP(Integer.parseInt(args[argindex + 1]), ce, rootdir, debug);
                if (mode > 0) {
                    ce.setMode(mode);
                }
            } else {
                System.err.println("Error: no port given");
                help();
                System.exit(3);
            }
        } catch (ConllException | IOException ex) {
            System.err.println("Error: " + ex.getMessage());
            System.exit(11);
        }
    }
}
