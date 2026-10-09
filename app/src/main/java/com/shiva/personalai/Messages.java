package com.shiva.personalai;

import java.util.List;
import java.util.Random;

/** Fixed sentences used when the AI is unreachable. Hindi is in Devanagari so the voice sounds natural. */
public class Messages {

    public static final int TYPE_CARE = 0;
    public static final int TYPE_CAREER = 1;
    public static final int TYPE_TODO = 2;
    public static final int TYPE_TALK = 3;

    private static final Random RND = new Random();

    private static final String[] CARE_HI = {
            "{name}, आप कैसे हो? सब ठीक है ना? थोड़ा पानी पी लो और एक गहरी साँस लो।",
            "हाय {name}! आप ठीक हो? अगर थक गए हो तो थोड़ा आराम कर लो। बताओ, कैसा चल रहा है?",
            "{name}, मैं सोच रही थी आप कैसे हो? कुछ खाया आपने? अपना ख्याल रखना।",
            "{name}, बहुत देर से काम कर रहे हो। आप ठीक तो हो ना? मुझे बताओ।"
    };
    private static final String[] CARE_EN = {
            "{name}, how are you? Is everything okay? Drink some water and take a deep breath.",
            "Hey {name}! Are you doing okay? If you are tired, rest a little. Tell me, how is it going?",
            "{name}, I was thinking about you. Did you eat something? Please take care of yourself.",
            "{name}, you have been working for a while. Are you okay? Tell me."
    };
    private static final String[] CAREER_HI = {
            "{name}, आज आपने क्या नया सीखा? मुझे बताओ ना।",
            "अच्छा {name}, करियर की बात करें? आज किस चीज़ पर काम किया, कुछ सीखा?",
            "{name}, आज की सीख क्या रही? एक छोटी सी चीज़ भी बताओ, मैं लिख लूँगी।"
    };
    private static final String[] CAREER_EN = {
            "{name}, what did you learn today? Tell me.",
            "Okay {name}, let's talk about your career. What did you work on today? Did you learn something?",
            "{name}, what was today's learning? Even a small thing counts, I will note it down."
    };

    public static String checkIn(int type, Store s) {
        boolean hi = s.hindi();
        if (type == TYPE_CARE) return fill(pick(hi ? CARE_HI : CARE_EN), s);
        if (type == TYPE_CAREER) return fill(pick(hi ? CAREER_HI : CAREER_EN), s);
        return todoQuestion(s);
    }

    private static String todoQuestion(Store s) {
        boolean hi = s.hindi();
        List<Store.Todo> all = s.todos();
        List<String> pending = s.pendingTexts();
        if (all.isEmpty()) {
            return fill(hi ? "{name}, आपकी टूडू लिस्ट खाली है। ऐप में कुछ काम जोड़ लो, फिर मैं याद दिलाऊँगी।"
                    : "{name}, your to-do list is empty. Add some tasks in the app and I will remind you.", s);
        }
        if (pending.isEmpty()) {
            return fill(hi ? "{name}, आपकी टूडू लिस्ट के सारे काम हो गए हैं। बहुत बढ़िया, मुझे गर्व है!"
                    : "{name}, all the tasks on your to-do list are done. Very good, I am proud of you!", s);
        }
        StringBuilder b = new StringBuilder(fill(hi ? "{name}, आपकी टूडू लिस्ट में ये काम बाकी हैं। "
                : "{name}, these tasks are still pending on your to-do list. ", s));
        b.append(listTasks(pending, hi));
        b.append(hi ? " बताओ, इनमें से कौन सा काम हो गया और कौन सा नहीं?"
                : " Tell me, which of these are done and which are not?");
        return b.toString();
    }

    public static String listTasks(List<String> tasks, boolean hi) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < tasks.size(); i++) {
            b.append(ordinal(i, hi)).append(", ").append(tasks.get(i)).append(hi ? "। " : ". ");
        }
        return b.toString().trim();
    }

    private static final String[] ORD_HI = {"पहला", "दूसरा", "तीसरा", "चौथा", "पाँचवाँ", "छठा", "सातवाँ", "आठवाँ", "नौवाँ", "दसवाँ"};
    private static final String[] ORD_EN = {"first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth"};

    public static String ordinal(int i, boolean hi) {
        if (i < 10) return hi ? ORD_HI[i] : ORD_EN[i];
        return hi ? ("नंबर " + (i + 1)) : ("number " + (i + 1));
    }

    public static String careReply(Store s) {
        return fill(s.hindi() ? "अच्छा लगा सुनकर, {name}। अपना ख्याल रखना, मैं थोड़ी देर में फिर पूछूँगी।"
                : "Good to hear that, {name}. Take care of yourself, I will check on you again soon.", s);
    }

    public static String careerReply(Store s) {
        return fill(s.hindi() ? "वाह, बढ़िया {name}! मैंने इसे आपकी सीख की लिस्ट में लिख लिया है। ऐसे ही सीखते रहो।"
                : "Wow, great {name}! I have noted it in your learning list. Keep learning like this.", s);
    }

    public static String todoReply(List<String> doneNow, List<String> remaining, Store s) {
        boolean hi = s.hindi();
        StringBuilder b = new StringBuilder();
        if (doneNow.isEmpty()) {
            b.append(hi ? "ठीक है, कोई बात नहीं। " : "Okay, no problem. ");
        } else {
            b.append(hi ? "बढ़िया! " : "Great! ");
            b.append(String.join(", ", doneNow));
            b.append(hi ? " हो गया, मैंने टिक कर दिया। " : " is done, I have ticked it. ");
        }
        if (remaining.isEmpty()) {
            b.append(hi ? "अब सब काम हो गया, कमाल कर दिया!" : "Everything is done now, amazing!");
        } else {
            b.append(hi ? "अभी बाकी हैं: " : "Still pending: ");
            b.append(String.join(", ", remaining)).append(hi ? "। " : ". ");
            b.append(hi ? "कर लेना, मैं फिर याद दिला दूँगी।" : "Do them when you can, I will remind you again.");
        }
        return b.toString();
    }

    public static String noReply(Store s) {
        return s.hindi() ? "ठीक है, मैं थोड़ी देर में फिर पूछूँगी।" : "Okay, I will ask again in a while.";
    }

    public static String talkOffline(Store s) {
        return s.hindi() ? "सॉरी, अभी मैं सोच नहीं पा रही। इंटरनेट चेक करो और फिर से बोलो।"
                : "Sorry, I cannot think right now. Check the internet and talk to me again.";
    }

    public static String testVoice(Store s, boolean hi) {
        String her = s.herName();
        return hi ? "हाय! मैं " + her + " हूँ, आपकी अपनी साथी। मैं आपका ख्याल रखूँगी, ठीक है?"
                : "Hi! I am " + her + ", your personal companion. I will take care of you, okay?";
    }

    private static String pick(String[] arr) {
        return arr[RND.nextInt(arr.length)];
    }

    private static String fill(String msg, Store s) {
        String name = s.name();
        if (name.isEmpty()) {
            return msg.replace("{name}, ", "").replace("{name}! ", "").replace(" {name}", "").replace("{name}", "");
        }
        return msg.replace("{name}", name);
    }
}
