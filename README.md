Parfait, je reprends exactement les fichiers listés dans ta capture pour générer le .jks. Voilà ce que tu avais :

blocked_certs.cert, backup_20240125/69447-29734668 pegasus-pnl-risk-swaps-adapter-uat01.fr.world.socgen.key pegasus-pnl-risk-swaps-adapter-uat01.fr.world.socgen.secret certs default.policy pegasus-pnl-risk-swaps-adapter-uat01.fr.world.socgen.key pegasus-pnl-risk-swaps-adapter-uat01.fr.world.socgen.key.decrypt.pem pegasus-pnl-risk-swaps-adapter-uat01.fr.world.socgen.pem public_suffix_list.dat

Pour créer un .jks, il te faut obligatoirement au minimum :
	•	Un certificat (ex: .crt ou .pem)
	•	La clé privée correspondante (ex: .key ou .pem)

Parmi les fichiers cités, ceux qui t’intéressent sûrement sont :
	•	pegasus-pnl-risk-swaps-adapter-uat01.fr.world.socgen.pem (certificat ou bundle cert + CA ?)
	•	pegasus-pnl-risk-swaps-adapter-uat01.fr.world.socgen.key (clé privée)
	•	pegasus-pnl-risk-swaps-adapter-uat01.fr.world.socgen.key.decrypt.pem (clé privée déchiffrée ?)
	•	Éventuellement d’autres fichiers .cert ou .pem pour la chaîne de confiance

Les autres (public_suffix_list.dat, .policy, etc.) ne sont pas utiles pour générer un keystore.

⸻

Script à utiliser

Supposons que :
	•	Ton certificat est : pegasus-pnl-risk-swaps-adapter-uat01.fr.world.socgen.pem
	•	Ta clé privée est : pegasus-pnl-risk-swaps-adapter-uat01.fr.world.socgen.key ou pegasus-pnl-risk-swaps-adapter-uat01.fr.world.socgen.key.decrypt.pem (à choisir selon laquelle est utilisable)
	•	(Optionnel) Si tu as une chaîne CA à ajouter, mets-la dans un fichier séparé, par exemple ca-chain.pem

1. Fusionne certificat et clé dans un PKCS12 (.p12)

openssl pkcs12 -export \
  -in pegasus-pnl-risk-swaps-adapter-uat01.fr.world.socgen.pem \
  -inkey pegasus-pnl-risk-swaps-adapter-uat01.fr.world.socgen.key \
  -out keystore.p12 \
  -name pegasus \
  -CAfile ca-chain.pem \
  -caname root

	•	Si tu n’as pas de ca-chain.pem, retire les deux dernières lignes.

2. Convertis le .p12 en .jks

keytool -importkeystore \
  -destkeystore keystore.jks \
  -srckeystore keystore.p12 \
  -srcstoretype PKCS12 \
  -alias pegasus

	•	On te demandera des mots de passe pour le keystore.

⸻

Cas particuliers
	•	Si tu n’as pas de CA chain, tu peux simplement faire :

openssl pkcs12 -export -in pegasus-pnl-risk-swaps-adapter-uat01.fr.world.socgen.pem -inkey pegasus-pnl-risk-swaps-adapter-uat01.fr.world.socgen.key -out keystore.p12 -name pegasus


	•	Si ta clé privée est chiffrée, utilise le .key.decrypt.pem.

⸻

Résumé étape par étape
	1.	Place-toi dans le dossier où sont les fichiers.
	2.	Exécute la commande openssl pour générer le .p12
	3.	Exécute la commande keytool pour générer le .jks

⸻

Besoin que je t’adapte la commande à d’autres fichiers de ta liste, ou c’est bon comme ça ?
Si tu veux, copie-colle ici le contenu du .pem et du .key (masque les infos sensibles !), ou précise si le certificat contient la chaîne complète (certificat serveur + intermédiaires + racine).

Dis-moi si tu veux une version copier-coller adaptée pile-poil à ton cas !