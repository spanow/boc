Parfait, il te suffit de remplacer `myalias` par `pegasus-gareway-dev-unipass` dans les deux commandes !
Voici **exactement** ce que tu dois taper :

---

### 1. Créer le PKCS12 (`.p12`) avec le bon alias

```bash
openssl pkcs12 -export -inkey ${cert_name}-key-decrypt.pem -in ${cert_name}.pem -out keystore-dev.p12 -name pegasus-gareway-dev-unipass
```

---

### 2. Convertir en JKS avec le même alias

```bash
keytool -importkeystore -deststorepass changeit -destkeypass changeit -destkeystore keystore-dev.jks -srckeystore keystore-dev.p12 -srcstoretype PKCS12 -srcstorepass [motdepasse_export] -alias pegasus-gareway-dev-unipass
```

> Remplace `[motdepasse_export]` par le mot de passe que tu as utilisé à l’étape 1.

---

### 3. Vérification (toujours avec le même alias)

```bash
keytool -list -v -keystore keystore-dev.jks -alias pegasus-gareway-dev-unipass
```

---

**Voilà, tu es paré !**
Dis-moi si tu veux un script tout-en-un ou si tu bloques à un endroit.
