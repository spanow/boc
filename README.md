

// =========================================================
// Notes & points d'attention (inclus):
// - Idempotency: tu peux injecter un header d'idempotence avec le runId pour éviter les doublons.
// - Logs: ne pas logger les bodies/headers sensibles (ici on ne log pas le contenu). Ajouter un mask si besoin.
// - TLS: si certificats custom, utiliser HttpClient avec SSLContext custom.
// - GZIP: si responses gzip, HttpClient gère. Pour stocker compressé, active tempFiles.gzip et adapte l'écriture (ici simplifiée, à étendre si nécessaire).
// - Very large files: si trop gros pour Files.readAllBytes côté envoi, remplacer par un streaming en chunk POST (HttpRequest.BodyPublishers.ofInputStream).
// - Transformation: insérer un step entre fetch et send qui lit/écrit les fichiers sans tout charger (Jackson streaming / NDJSON).
// - Retry/Backoff/429: gérés basiquement ici.
// - Metrics/Tracing: brancher micrometer + headers de corrélation.
// - Sécurité: secrets via Vault/ENV; jamais en clair dans logs.
// - Tests: simuler avec WireMock.


Top ! J’ai posé un module **API→API** ultra-léger dans le canvas : job, settings, 3 tasklets (fetch → send → purge), un client HTTP simple (GET/POST + headers + query + body), pagination, retries/backoff, throttling, auth (Basic / API-Key / OAuth2), fichiers temporaires chunkés (pour ménager la RAM), + cleanup en cas d’échec. Il y a aussi un **snippet YAML** prêt à coller.

Tu peux :

* changer `flow-type: API_TO_API` dans ton yml,
* remplir `batch-job.api2api.source` et `destination`,
* lancer le job : ça stream → écrit en `/tmp` → (optionnellement tu ajoutes ton step de conversion) → envoie → purge.

Si tu veux une variante (NDJSON, mapping champ-par-champ, envoi par paquets de N enregistrements, ou streaming direct sans `readAllBytes`) dis-moi, je te l’ajoute direct.

