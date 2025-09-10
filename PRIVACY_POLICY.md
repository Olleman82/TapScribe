# Integritetspolicy för TapScribe (Mic‑bubbla)

Senast uppdaterad: 2025-09-09

Denna app är ett hobbyprojekt och tillhandahålls utan kostnad. Den samlar inte in personuppgifter för egna syften, säljer dem inte vidare och visar inga annonser. Appen fungerar lokalt på din enhet och skickar endast den text du själv dikterar vidare till den AI‑tjänst du konfigurerar (Google Gemini) för att få ett svar. Läs denna policy innan du använder appen.

## Vilken data hanteras
- Röstinnehåll: Ditt tal transkriberas till text av Androids inbyggda taligenkänning (eller Google‑UI). Själva ljudet hanteras av systemets rösttjänst; denna app sparar inte ljudfiler.
- Transkriberad text: Den text du just talat kan – efter ditt val – skickas till Googles Gemini API för att generera ett svar. Texten skickas då över internet till Googles servrar. Svaren från API:t visas för dig i appen och kan kopieras/klistras in i andra appar.
- Egna prompter (databas lokalt): Dina sparade prompter lagras lokalt i appens lokala databas på enheten. De skickas inte någonstans av appen.
- Urklipp: När du kopierar eller auto‑klistrar använder appen Androids urklippsfunktion för att lägga text i urklipp och – om du aktiverat tillgänglighetstjänsten – försöka klistra in i fokuserat textfält.

## Behörigheter
- Mikrofon: Krävs för att kunna transkribera tal till text.
- Visa över andra appar: Krävs för att den flytande mikrofonbubblan ska synas ovanpå andra appar.
- Tillgänglighetstjänst (valfritt): Om du aktiverar den kan appen försöka klistra in svaret automatiskt i det textfält som har fokus.
- Internet: Krävs för anrop till AI‑tjänsten (Gemini) och för ev. nätverksfunktioner.

## Delning med tredje part
- Google (Gemini API, taligenkänning): När du använder AI‑funktionen skickas din text till Google enligt deras villkor. Läs Googles integritetspolicy och villkor för respektive tjänst.

## Lagringstid
Appen lagrar inte dina röstinspelningar. Dina prompter ligger kvar lokalt tills du själv tar bort dem eller avinstallerar appen. Urklipp rensas enligt Androids ordinarie beteende eller när du kopierar något nytt.

## Säkerhet
Datan skickas till AI‑tjänsten via HTTPS. Ingen ytterligare kryptering tillämpas av appen. Hantera din API‑nyckel varsamt och dela den inte.

## Dina val
- Använd appen utan auto‑klistra om du inte vill aktivera tillgänglighetstjänsten.
- Använd inte AI‑funktionen om du inte vill skicka text till en tredjepart.
- Radera prompter du inte längre vill ha kvar.

## Barn
Appen är inte avsedd för användare under 13 år.

## Kontakt
Frågor? Skapa ett ärende (issue) i GitHub‑repo eller kontakta utvecklaren via den angivna hemsidan i appen.

---
Denna policy kan uppdateras. Fortsatt användning av appen efter ändringar innebär att du godkänner den uppdaterade policyn.
