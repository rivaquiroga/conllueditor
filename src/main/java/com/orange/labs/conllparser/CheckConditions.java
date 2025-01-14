/* This library is under the 3-Clause BSD License

Copyright (c) 2018-2021, Orange S.A.

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
 @version 2.12.2 as of 17th September 2021
 */

package com.orange.labs.conllparser;

import java.util.Map;
import java.util.Set;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;


public class CheckConditions {


    public static boolean evaluate(String condition, Map<String, Set<String>> wordlists, ConllWord cword) throws Exception {
        ConditionsLexer lexer = new ConditionsLexer(CharStreams.fromString(condition));
        lexer.addErrorListener(new GrammarErrorListener());

        // we can see tokens only once !
//        for (Token tok : lexer.getAllTokens()) {
//           System.err.println("token: " + tok);
//        }
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        ConditionsParser parser = new ConditionsParser(tokens);
        parser.addErrorListener(new GrammarErrorListener());
        ParseTree tree = parser.prog(); // parse

        CEvalVisitor eval = new CEvalVisitor(cword, wordlists);
        boolean rtc = eval.visit(tree);
        //System.err.println("rtc " + rtc);
        return rtc;
    }
    
    
    

 
    public static void main(String[] args) throws Exception {
        if (args.length == 0)  {
            System.err.println("Usage: Condition '<condition>' '<conllu word>'");
        } else {
      
            
        ConditionsLexer lexer = new ConditionsLexer(CharStreams.fromString(args[0]));
         lexer.addErrorListener(new GrammarErrorListener());

        // we can see tokens only once !
//        for (Token tok : lexer.getAllTokens()) {
//           System.err.println("token: " + tok);
//        }
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        ConditionsParser parser = new ConditionsParser(tokens);
        parser.addErrorListener(new GrammarErrorListener());
        
        
        
        ParseTree tree = parser.prog(); // parse
        
        ConllWord cword;
        if (args.length == 1) {
             cword = new ConllWord("1\trules\trule\tNOUN\tNNS\tNumber=Plur|Gender=Neut\t3\tnsubj\t_\tSpaceAfter=No", null, null);
          
        } else {
             cword = new ConllWord(args[1].replaceAll(" +", "\t"), null, null);
        }
        System.err.println(cword);
        CEvalVisitor eval = new CEvalVisitor(cword, null);
        boolean rtc = eval.visit(tree);
        System.err.println("rtc " + rtc);
        }
    }

}
