// SPDX-License-Identifier: MPL-2.0
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.sharddeck.homoglyph.tools;

import io.sharddeck.homoglyph.core.*;
import java.io.*;
import java.nio.charset.*;
import java.util.*;

/** Offline Unicode configuration and analysis tooling. */
public final class Main {
    public static void main(String[] args) throws Exception {
        try { run(args); }
        catch (IllegalArgumentException e) { System.err.println(e.getMessage()); System.exit(2); }
    }
    private static void run(String[] args) throws Exception {
        if(args.length==0) throw new IllegalArgumentException("Usage: profiles | validate [key=value ...] | analyze [key=value ...]");
        if(args[0].equals("profiles")) {
            if(args.length!=1) throw new IllegalArgumentException("profiles takes no settings");
            try(InputStream input=Main.class.getResourceAsStream("/io/sharddeck/homoglyph/data/profiles.json")) { input.transferTo(System.out); }
            return;
        }
        Map<String,String> settings=new LinkedHashMap<>();
        for(int i=1;i<args.length;i++) {
            int split=args[i].indexOf('=');
            if(split<1 || settings.putIfAbsent(args[i].substring(0,split),args[i].substring(split+1))!=null)
                throw new IllegalArgumentException("Expected unique key=value settings");
        }
        Configuration config=new Configuration(settings);
        if(args[0].equals("validate")) { System.out.println("Valid homoglyph configuration: "+config.profile); return; }
        if(!args[0].equals("analyze")) throw new IllegalArgumentException("Unknown command");
        int max=config.limits.maxInputUtf16Units();
        CharsetDecoder decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT);
        try(Reader reader=new BufferedReader(new InputStreamReader(System.in,decoder));
            TokenProcessor processor=DefaultEngine.SHARED.newProcessor(config.profile,config.limits,config.preserveOriginal)) {
            char[] input=new char[max]; int length=0,c;
            while((c=reader.read())!=-1) {
                if(c=='\n') { emit(processor,input,length); length=0; }
                else { if(length==max) throw new IllegalArgumentException("Offline command input exceeds "+max+" UTF-16 units"); input[length++]=(char)c; }
            }
            if(length!=0) emit(processor,input,length);
        }
    }
    private static void emit(TokenProcessor processor,char[] input,int length) {
        processor.reset(); processor.prepare(input,0,length);
        List<String> outputs=new ArrayList<>(2);
        while(processor.increment()) outputs.add(new String(processor.buffer(),processor.offset(),processor.length()));
        System.out.println(array(outputs));
    }
    private static String array(List<String> values) {
        StringJoiner result=new StringJoiner(",","[","]");
        for(String value:values) {
            StringBuilder escaped=new StringBuilder("\"");
            for(int i=0;i<value.length();i++) {
                char c=value.charAt(i);
                if(c=='"' || c=='\\') escaped.append('\\').append(c);
                else if(c<32 || Character.isSurrogate(c)) escaped.append(String.format(Locale.ROOT,"\\u%04x",(int)c));
                else escaped.append(c);
            }
            result.add(escaped.append('"').toString());
        }
        return result.toString();
    }
}
