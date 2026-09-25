# Build APK dal telefono con GitHub Actions

Questo progetto contiene il workflow `.github/workflows/build-apk.yml`.

Dopo aver caricato tutti i file del progetto in un repository GitHub:
1. Apri la scheda **Actions** del repository.
2. Apri **Build Android APK**.
3. Premi **Run workflow** e conferma.
4. Al termine, apri l'esecuzione completata.
5. Nella sezione **Artifacts**, scarica **ScopaAdvisorPro-APK**.
6. Estrai il file ZIP scaricato: all'interno troverai `app-debug.apk`.
7. Apri l'APK su Android e autorizza l'installazione da quella sorgente se richiesto.

Nota: il riconoscimento automatico delle carte necessita ancora del profilo grafico della specifica app di Scopa usata.
