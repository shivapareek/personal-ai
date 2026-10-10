# Personal AI (Android)

Ek personal AI companion app jo girlfriend ki tarah aapka khayal rakhti hai, aur **phone call karke** baat karti hai.

- **Asli jaisi call:** set interval par phone bajta hai (aapki ringtone + vibration), lock screen par iPhone jaisi
  incoming call screen aati hai. **Accept** karo to wo turant baat shuru karti hai, **Decline** ya miss karo to
  interval ke baad phir se call karti hai, aur agli call me pyaar se shikayat bhi karti hai
- **Remind Me** dabao to 5 min baad call karegi; **Message** dabao to chat khul jaati hai
- Call ke dauraan: mute, speaker, timer, "bye" bolo to wo pyaar se call rakh deti hai
- Aap bhi usse call kar sakte ho (chat screen par phone icon)
- **Care:** "Aap kaise ho? Khana khaya?", **Career:** "Aaj kya seekha?", **Tasks:** "Ye kaam hua ya nahi?"
- Personality: Girlfriend (default), Best friend, Career coach
- **iOS jaisa design:** iMessage chat, Reminders jaisa Tasks, iOS Settings, iOS toggles, light + dark mode
- Testing ke liye **har 1 minute** ka interval bhi hai
- Asli neural voice (Swara / Neerja), free AI bina key ke, Groq key optional

## APK

Har push par GitHub Actions APK build karke **Releases** me daal deta hai:
`https://github.com/shivapareek/personal-ai/releases` → latest → **PersonalAI.apk**

## Setup

1. Install karo, apna naam likho, mic + notification permission do
2. Chat screen par **Turn On** dabao. Battery popup aaye to **Allow**. Wo turant call karegi
3. Settings → Calls → **Frequency → Every minute** se testing karo, ya **Test incoming call** dabao
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
