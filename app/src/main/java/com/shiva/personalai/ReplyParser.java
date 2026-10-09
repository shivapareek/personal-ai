package com.shiva.personalai;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Figures out (without AI) which pending tasks the user said are done. */
public class ReplyParser {

    private static final String[][] ORDINALS = {
            {"पहला", "पहली", "पहले", "एक", "first", "one", "pehla", "pehli", "ek"},
            {"दूसरा", "दूसरी", "दूसरे", "दो", "second", "two", "dusra", "dusri", "do"},
            {"तीसरा", "तीसरी", "तीसरे", "तीन", "third", "three", "teesra", "teen"},
            {"चौथा", "चौथी", "चौथे", "चार", "fourth", "four", "chautha", "char"},
            {"पाँचवाँ", "पांचवा", "पांचवां", "पाँचवा", "पाँच", "पांच", "fifth", "five", "paanch"},
            {"छठा", "छठी", "छठे", "छह", "छः", "sixth", "six", "chhata", "chhe"},
            {"सातवाँ", "सातवां", "सातवा", "सात", "seventh", "seven", "saat"},
            {"आठवाँ", "आठवां", "आठवा", "आठ", "eighth", "eight", "aath"},
            {"नौवाँ", "नौवां", "नौवा", "नौ", "ninth", "nine", "nau"},
            {"दसवाँ", "दसवां", "दसवा", "दस", "tenth", "ten", "das"}
    };

    private static final String[] ALL_DONE = {"सब हो गया", "सारे हो गए", "सब कर लिया", "सब कर दिया", "सब हो गए",
            "all done", "everything is done", "all of them", "sab ho gaya", "sab kar liya"};
    private static final String[] NEGATIVE = {"नहीं", "नही", "बाकी", "पेंडिंग", "not", "pending", "nahi", "nhi", "baaki", "baki"};

    private static final Pattern NUMBER = Pattern.compile("\\d+");

    /** Returns indices (into pending) of tasks the user says are done. */
    public static List<Integer> doneIndices(String reply, List<String> pending) {
        List<Integer> done = new ArrayList<>();
        if (reply == null || pending.isEmpty()) return done;
        String text = reply.toLowerCase().trim();

        for (String phrase : ALL_DONE) {
            if (text.contains(phrase)) {
                for (int i = 0; i < pending.size(); i++) done.add(i);
                return done;
            }
        }

        String[] clauses = text.split("[,।.;!?]|\\sऔर\\s|\\sलेकिन\\s|\\sपर\\s|\\sand\\s|\\sbut\\s|\\saur\\s|\\slekin\\s");
        for (String clause : clauses) {
            String c = " " + clause.trim() + " ";
            if (c.trim().isEmpty()) continue;
            boolean negative = false;
            for (String n : NEGATIVE) {
                if (c.contains(n)) { negative = true; break; }
            }
            for (int idx : referencedTasks(c, pending)) {
                if (!negative && !done.contains(idx)) done.add(idx);
            }
        }
        return done;
    }

    private static List<Integer> referencedTasks(String clause, List<String> pending) {
        List<Integer> refs = new ArrayList<>();
        // ordinal words: "pehla", "दूसरा", "first" ...
        for (int i = 0; i < Math.min(pending.size(), ORDINALS.length); i++) {
            for (String w : ORDINALS[i]) {
                if (clause.contains(" " + w + " ") || clause.contains(" " + w + "।")) {
                    if (!refs.contains(i)) refs.add(i);
                    break;
                }
            }
        }
        // digits: "1", "2" ...
        Matcher m = NUMBER.matcher(clause);
        while (m.find()) {
            try {
                int n = Integer.parseInt(m.group()) - 1;
                if (n >= 0 && n < pending.size() && !refs.contains(n)) refs.add(n);
            } catch (NumberFormatException ignored) {
            }
        }
        // task keywords: any word (3+ chars) of the task text spoken in the clause
        for (int i = 0; i < pending.size(); i++) {
            if (refs.contains(i)) continue;
            for (String word : pending.get(i).toLowerCase().split("\\s+")) {
                if (word.length() >= 3 && clause.contains(word)) {
                    refs.add(i);
                    break;
                }
            }
        }
        return refs;
    }
}
