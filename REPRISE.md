# Sillage — reprise du développement

Le dépôt a été créé avec Kotlin Toolchain 0.14.0, sans Gradle. Le wrapper officiel JetBrains est adapté pour garder ses caches dans .tools/. Son archive téléchargée a été vérifiée (SHA-256 : 69112cebc1fdb14c92a213244182d0834896e71b3d9411b8e8c18540da2884a6).

## Demande utilisateur

Moteur 3D Kotlin/JVM avec Vulkan, physique écrite en Kotlin sans moteur C++, API simple pour enfants et import de modèles Blender GLB. Démo originale inspirée de Wind Waker : petite île, aplats, caméra troisième personne, clavier/souris, gravité et collisions. Utiliser Kotlin Toolchain 0.14, aucun Gradle. L'utilisateur autorise à poursuivre sans Blender pour le moment.

## Sources présentes

- engine/src/sillage : backend Vulkan LWJGL, chargeur GLB Kotlin/Gson, géométrie, contrôleur physique cinématique à 120 Hz, caméra, boucle de jeu, HUD bitmap.
- demo/src/sillage/demo/Main.kt : DSL française, île, marin, six coquillages.
- demo/resources/models : trois GLB générés avec tools/create_assets.py (terrain, village, marin).
- tools/create_island.py : variante Blender, non exécutée avec succès dans le sandbox.
- shaders Vulkan et SPIR-V compilés avec succès via tools/shaders.sh.

## État réel et travail restant

NE PAS présenter le moteur comme compilé ou la démo comme lancée. Aucun build Kotlin n'a abouti dans la session précédente ; il faut compiler, corriger les éventuelles erreurs, écrire/exécuter les tests utiles de physique et GLB, lancer une démo Vulkan hors écran puis interactive et vérifier une capture.

Le shell Codex échouait avant même Bash/Fish sur bwrap: execvp /home/ruben/.codex/packages/app-server-daemon/releases/0.169.1-x86_64-unknown-linux-musl/bin/codex: No such file or directory. Le runtime Node permettait de lire/écrire les fichiers et lancer des processus, mais sans DNS, écran, GPU matériel, JDK 25 complet ni dépendances complètes Blender. Java 26 fonctionne. Le JDK 25 est configuré dans module.yaml ; utiliser un JDK 25 complet via JAVA_HOME, ou laisser la toolchain le provisionner.

Sur l'hôte de l'utilisateur, Blender 5.3.0 fonctionne (test confirmé par l'utilisateur). CachyOS, Fish. Dans l'ancien sandbox, vulkaninfo ne trouvait que Lavapipe/llvmpipe Vulkan 1.4 ; Xvfb et glslc étaient disponibles. Les autorisations d'escalade n'ont pas réparé l'exécutable Codex absent.

## Commandes à lancer dans un environnement fonctionnel

```sh
./kotlin build
./kotlin test
./kotlin run --help
# Vérifier le passage des arguments avec --help puis :
./kotlin run -- --headless --smoke --frames 180 --capture build/sillage.png
./kotlin run
```

La CLI et la syntaxe des arguments run doivent être vérifiées avec la version 0.14.0. Le moteur accepte --headless, --smoke, --frames N, --capture fichier.png, --validation. Les couches Vulkan de validation manquaient dans l'ancien sandbox.

## Limites de la première implémentation

Maillages GLB statiques triangulés, matériaux de couleur et COLOR_0, hiérarchies et TRS. Textures, squelettes, animations et extensions requises sont rejetés explicitement. Physique de personnage seulement (cercle horizontal et intervalle vertical), terrains triangulés et obstacles AABB ; pas de corps rigides généraux. Un seul frame Vulkan en vol, rendu interne fixe 960x600 avec blit vers fenêtre redimensionnable. Aucun moteur natif, Assimp ou Bullet.
