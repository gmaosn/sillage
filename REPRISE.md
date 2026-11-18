# Sillage — reprise

## Pour reprendre

Sillage est un moteur 3D en Kotlin pur, plus ergonomique et agréable que KorGE, en 3D par défaut, avec son propre moteur physique. Il doit être aussi rapide que la JVM le permet et prêt pour Valhalla.

Choix du propriétaire (18 novembre 2026) : API publique en anglais ; Linux d'abord (Wayland, X11 en secours, x86-64 et ARM64 du Pi), avec une couche fenêtre prête pour d'autres systèmes plus tard ; aucune dépendance tierce (seules les bibliothèques du système : libvulkan, libwayland-client, libc, appelées par FFM).

Construire uniquement avec `./kotlin` (Kotlin Toolchain 0.14.0), jamais Gradle. Le JDK 25 du système n'a pas de javac : la toolchain fournit le sien.

```sh
./kotlin build
./kotlin test
./kotlin run -m demo --jvm-args=--enable-native-access=ALL-UNNAMED -- --headless --smoke --frames 180 --capture build/captures/smoke.png
./kotlin run -m demo --jvm-args=--enable-native-access=ALL-UNNAMED
```

La toolchain 0.14 ne permet de fixer des options JVM que pour les tests (`settings.jvm.test.freeJvmArgs`, déjà réglé) ; pour `run`, il faut `--jvm-args`. Sans `--enable-native-access`, la JVM avertit à chaque appel FFM.

La démo accepte `--headless`, `--smoke`, `--frames N`, `--capture fichier.png`, `--validation` (les couches de validation Vulkan ne sont pas installées sur le portable).

## Plan

0. Maths prêtes pour Valhalla et JSON maison, JOML et Gson retirés. **Fait.**
1. Vulkan appelé par FFM (java.lang.foreign) à la place de LWJGL. **Fait** : capture identique au pixel près à `tools/reference/demo-smoke-180.png`.
2. Fenêtre Wayland par FFM, souris capturée (pointer-constraints, relative-pointer), touches par position physique (ZQSD en AZERTY = WASD en QWERTY), secours X11 pour Xvfb ; GLFW retiré, zéro dépendance tierce.
3. Moteur physique : corps rigides (sphère, boîte, capsule, maillage statique GLB), phase large et phase fine, solveur par impulsions séquentielles avec démarrage à chaud, frottement, rebond, sommeil. Bancs de mesure : pyramide de caisses stable, 10 000 corps, déterminisme.
4. Personnage, tirs de rayons et déclencheurs sur cette physique ; la démo de l'île portée dessus.
5. API plus agréable que KorGE : un fichier, 3D sans réglage (caméra, lumière, ciel par défaut), physique en une ligne ; deuxième démo à caisses, tonneaux et ballon.
6. Passe de performance : plusieurs images en vol, instancing, zéro allocation par image (JFR), cache AOT, en-têtes compacts, ZGC générationnel, API Vector, essai sur une build Valhalla.

## État (18 novembre 2026)

Le prototype a été écrit le matin du 18 par Codex sans jamais compiler. Repris : build réparé (bug de précédence `i and 1 == 0` dans `Geometrie.boite`), puis étape 0.

Maths dans `engine/src/sillage/math` : `Vec3`, `Quat`, `Mat4` immuables, sans identité, marqués `@ValueCandidate` pour devenir des `value class` quand Kotlin aura les classes valeur à plusieurs champs sur Valhalla. Ne jamais les comparer avec `===` ni se synchroniser dessus. Les boucles chaudes garderont leurs données dans des tableaux primitifs.

JSON dans `engine/src/sillage/io/Json.kt` : strict (RFC 8259), profondeur bornée à 256, les nombres gardent leur texte pour que `toFloat()` arrondisse une seule fois.

38 tests verts (physique du personnage, géométrie, GLB, maths, JSON). Démo vérifiée hors écran sur l'Arc B390 ; le chemin fenêtré a été vérifié sous Xvfb avant l'étape 0 (llvmpipe, Xvfb n'a pas DRI3).

Étape 1 : `engine/src/sillage/vk/`. `Vulkan.kt` charge `libvulkan.so.1`, une poignée FFM `static final` par forme de signature (le JIT l'inline), l'adresse de la fonction passée en argument ; les fonctions de périphérique viennent de `vkGetDeviceProcAddr`. `Struct.kt` décrit les structures C (alignements, champs imbriqués « extent.width », tableaux « srcOffsets[1].x ») et fournit `NativeStack`, une pile native réutilisée (aucune allocation native par image). `Structs.kt` et `Constants.kt` : 71 structures et 127 constantes écrites d'après la référence Odin (`/usr/lib/odin/vendor/vulkan`, générée depuis vk.xml, absent du portable) ; un test de transition les a comparées à LWJGL par réflexion (539 décalages de champs, 127 valeurs, tout identique) avant d'être supprimé avec LWJGL Vulkan. Le rendu reçoit une `SurfaceSource` (extensions, surface, taille) : la fenêtre GLFW provisoire ne fournit plus que ses poignées Wayland ou X11, la surface est créée par notre code.

Non vérifié à l'étape 1 : le rappel de validation (upcall FFM) est écrit mais les couches de validation ne sont pas installées (`vulkan-validation-layers`, à installer par le propriétaire s'il le veut) ; la présentation Wayland n'a pas été lancée sur le vrai bureau (seulement X11 sous Xvfb, llvmpipe). Les appels `critical` (sans transition d'état du thread) pour les vkCmd* sont repoussés à l'étape 6 : ils sont incompatibles avec le rappel de validation.

Le passage de JOML aux maths maison change 2 pixels sur 576 000 (bord d'un rocher, arrondi de la caméra) ; la position finale du personnage est la même. La référence a été remplacée par la capture de l'étape 0.

Le code hérité de Codex est encore en français (`Monde`, `Joueur`, `Physique`, `Objet`…) ; il sera renommé en anglais quand l'API sera refaite (étape 5), la physique du personnage sera remplacée aux étapes 3 et 4.

## Limites actuelles

Maillages GLB statiques triangulés, couleurs de matériau et COLOR_0, hiérarchies et TRS ; textures, squelettes, animations et extensions requises refusés explicitement. Physique : personnage seulement (cercle horizontal, intervalle vertical), terrains triangulés et boîtes fixes. Une seule image en vol, rendu interne 960×600 recopié vers la fenêtre.

Outils : `tools/shaders.sh` (glslc) recompile les SPIR-V ; `tools/create_assets.py` a produit les trois GLB de la démo ; `tools/create_island.py` est une variante Blender jamais exécutée.
