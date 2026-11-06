# Règles R8 propres à l'appli.
#
# Les bibliothèques (Compose, Play Services, Hilt, DataStore...) embarquent leurs propres
# règles consommateur : ne JAMAIS les re-garder ici en bloc. N'ajouter que ce qui est lu
# par réflexion depuis NOTRE code, avec le paquet le plus étroit.

# Traces lisibles dans Android vitals (Play Console) une fois le mapping envoyé.
-keepattributes SourceFile,LineNumberTable
