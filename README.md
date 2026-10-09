# Personal AI (Android)

Ek chhota sa Android app jo ek ladki ki awaaz me aapse baar-baar baat karta hai:

- **Care**: "Aap kaise ho? Aap theek ho? Paani piya?"
- **Career**: "Aaj aapne kya seekha?" (aapka jawab app me save hota hai)
- **Todo**: aapki todo list bolti hai aur puchti hai "konsa kaam ho gaya, konsa nahi?" aur aapke bolne par tick kar deti hai

Sab kuch **bina AI ke** chalta hai (phone ka built-in Text-to-Speech aur Speech Recognition, free).
AI sirf optional hai: free Groq API key dalne par wo aapke jawab ko samajh kar natural reply degi.

## APK kahan se milega

Har push par GitHub Actions APK build karke **Releases** me daal deta hai:

1. Repo ke **Releases** page par jao: `https://github.com/shivapareek/personal-ai/releases`
2. Latest release me **PersonalAI.apk** download karo
3. Phone me install karo ("Unknown sources / Is source se install allow karo" on karna padega)

Manually build karne ke liye: **Actions** tab → **Build APK** → **Run workflow**.

## Setup (phone me)

1. App kholo, **mic** aur **notification** permission allow karo
2. **Settings** me:
   - apna naam likho
   - bhasha chuno (Hindi / English)
   - **Voice** dropdown me alag-alag voices **"Voice test karo"** se suno aur jo ladki ki awaaz ho wo chun lo
     (Google TTS me `hi-in-x-hia`, `hi-in-x-hid` jaise naam hote hain; test karke dekho)
   - kitne minute me puche (default 60) aur kis time se kis time tak (default 8 se 22)
   - **"Battery optimization band karo"** zaroor dabao, warna phone service ko maar deta hai
   - Save
3. Main screen par todo list me kaam add karo
4. **Start** dabao. **"Abhi puchho"** se turant test kar sakte ho.

Phone me **Google Text-to-speech** (Speech Services by Google) hona chahiye aur Hindi voice data download hona chahiye
(Phone Settings → System → Languages → Text-to-speech → Google → Install voice data → Hindi).

## Free AI (optional)

1. https://console.groq.com par free account banao → **API Keys** → key banao (`gsk_...`)
2. App Settings me **API key** me paste karo, Save
3. Default model `llama-3.3-70b-versatile` aur URL Groq ka hai. Koi bhi OpenAI-compatible endpoint
   (OpenRouter ke free models, etc.) use kar sakte ho URL/model badal kar.

Key khali rakhoge to app fixed sentences bolta hai aur todo ka jawab simple rules se samajhta hai
("pehla ho gaya", "2 aur 3 done", "sab ho gaya", task ka naam bolna, etc.).

## Kaise kaam karta hai

- `CheckInService` – foreground service; `AlarmManager` se har interval par jagta hai, TTS se bolta hai,
  `SpeechRecognizer` se sunta hai, todo update karta hai
- `Messages` – saare Hindi/English sentences
- `ReplyParser` – bina AI ke "konsa kaam ho gaya" samajhna
- `AiClient` – optional OpenAI-compatible chat call (Groq default)
- `Store` – settings, todo list, "aaj kya seekha" log (SharedPreferences)

Koi external library nahi, sirf Android framework. `keystore/personal-ai.jks` sirf personal install ke liye hai
taaki har build ka signature same rahe aur update install ho sake.
