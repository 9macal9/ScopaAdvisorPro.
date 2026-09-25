# Scopa Advisor Pro 2.0

App Android companion per Scopa con:

- overlay spostabile sopra altre app;
- cattura schermo tramite Android MediaProjection (consenso esplicito di Android);
- riconoscimento automatico a template delle carte;
- conteggio delle carte viste;
- import automatico mano/tavolo nell'advisor;
- simulazione Monte Carlo e consiglio della carta;
- modalità manuale sempre disponibile.

## Avvio

1. Aprire il progetto in Android Studio.
2. Sincronizzare Gradle e generare l'APK.
3. Installare l'APK sul telefono.
4. Aprire Scopa Advisor Pro e premere **AVVIA AUTO**.
5. Concedere "Mostra sopra altre app".
6. Accettare la cattura schermo Android.
7. Aprire il gioco di Scopa. L'overlay resta in alto e può essere trascinato.

## Riconoscimento automatico: profilo del gioco

Il riconoscimento non può essere realmente universale: giochi diversi usano grafiche e posizioni differenti. Il progetto implementa un recognizer a template senza dipendenze esterne.

Aggiungere 40 immagini PNG ritagliate in:

`app/src/main/assets/card_templates/`

con nomi `D_1.png...D_10.png`, `C_1.png...`, `S_1.png...`, `B_1.png...`.

Gli slot predefiniti sono definiti in `CardRecognitionEngine.kt` come coordinate normalizzate. Sono pensati per un layout verticale generico con 3 carte in mano e area tavolo centrale. Per un'app specifica vanno adattati alla sua UI.

Se non sono presenti template, l'overlay segnala: `AUTO: aggiungi il profilo grafico delle carte` e l'uso manuale continua a funzionare.

## Privacy

La cattura avviene localmente sul dispositivo. Il progetto non invia screenshot o carte a server remoti.

## Limiti

- Se il gioco usa animazioni, prospettiva, sovrapposizioni o carte molto piccole, i template possono richiedere ritagli accurati.
- La percentuale di vittoria è una stima simulata, non una garanzia.
- Alcuni giochi online possono vietare overlay/assistenti nelle proprie condizioni d'uso: verificare le regole del gioco utilizzato.
