# Personal AI (Android)

Ek personal AI companion app: ek ladki ki asli jaisi awaaz me wo khud aapse baat karti hai, aapka khayal
rakhti hai, career ke baare me puchti hai aur aapki todo list follow-up karti hai.

- **Khud puchti hai** (auto check-in har 30 min, time aap set karo): "Aap kaise ho?", "Aaj kya seekha?",
  "Ye kaam baaki hain, konsa ho gaya?"
- **Aapka jawab sunti hai** (mic) aur AI se natural reply deti hai, baat aage badhati hai, pichhli baatein yaad rakhti hai
- **Todo list** bolne par tick ho jaati hai ("pehla ho gaya", "gym done")
- **Mic dabao** aur kuch bhi baat karo
- **Asli neural voice** (Microsoft Edge ki free Swara / Neerja voice), net na ho to phone ki voice
- **Free AI** bina key ke (Pollinations). Behtar AI ke liye Groq ki free key (optional)
- Professional flat UI (light + dark mode), bottom navigation: Home, Tasks, Journal, Settings
- Background reliable: exact alarms, foreground service, recent apps se hatane par restart, reboot ke baad wapas,
  har 15 min safety-net job, check-in ka message notification me bhi dikhta hai (Reply button ke saath)

## APK

Har push par GitHub Actions APK build karke **Releases** me daal deta hai:
`https://github.com/shivapareek/personal-ai/releases` → latest → **PersonalAI.apk**

## Setup

1. Install karo, apna naam likho, mic + notification permission do
2. Home par switch on karo. Battery popup aaye to **Allow**
3. Wo turant hello bolegi. Mic dabao aur jawab do, ya type karo. **Check in now** se kabhi bhi test karo
4. Settings → Background reliability: sab "Allowed / Unrestricted" hona chahiye. Xiaomi/Oppo/Vivo/Realme par
   **Autostart** on karo aur Recent apps me app ko **Lock** karo
5. Default time 7 AM se 12 AM (midnight) hai, Settings → Check-ins me badal sakte ho

Phone me Google app / Speech Services hona chahiye (speech recognition ke liye), aur Hindi offline voice
ke liye Settings → Languages → Text-to-speech → Google → Hindi voice data.

## Technical

- Koi androidx nahi, sirf Android framework + OkHttp
- `CheckInService` – foreground service, AlarmManager schedule, conversation loop (bolna → sunna → sochna)
- `Speaker` – online neural TTS (`EdgeTts`, MP3 cache) + device TTS fallback
- `AiClient` – OpenAI-compatible chat (Pollinations free by default, Groq with key), persona + memory + JSON output
- `ReplyParser` / `Messages` – bina AI ke fallback
- `Store` – settings, todos, learnings, chat history (SharedPreferences)

Edge neural voice aur Pollinations dono unofficial/free services hain; band ho jayein to app device voice
aur fixed sentences par chalti rehti hai. `keystore/personal-ai.jks` sirf personal install ke liye hai.
