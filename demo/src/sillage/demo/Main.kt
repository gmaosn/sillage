package sillage.demo

import sillage.jeu
import sillage.math.Vec3
import sillage.physics.Box
import sillage.physics.Capsule

/** Le jeu tient dans ce fichier ; les détails Vulkan restent dans le moteur. */
fun main(args: Array<String>) = jeu("Sillage",args) {
    ocean()
    terrain("models/terrain.glb")
    val village=modele("models/village.glb")
    // Le toit est solide tel qu'il est dessiné : depuis les tonneaux, on peut sauter dessus et y grimper.
    solide(village,"toit")
    // Modèles animés : les clips viennent des fichiers GLB.
    modele("models/palmiers.glb").animer("vent")
    val port=modele("models/port.glb").animer("vie")
    // La barque se visite et tangue avec la houle : son pont elliptique en deux boîtes, son mât.
    corpsSurNoeud(port,"bateau",Box(.7f,.25f,.75f),Vec3(0f,-.04f,0f))
    corpsSurNoeud(port,"bateau",Box(.42f,.25f,1.4f),Vec3(0f,-.04f,0f))
    corpsSurNoeud(port,"bateau",Capsule(.07f,1.75f),Vec3(0f,1.85f,.2f))
    modele("models/ciel.glb").animer("vol")
    // Volumes simples séparés du décor : modifier ces valeurs pour aménager l'île.
    // Maison jusqu'au bord du toit : l'avant-toit est à 1,65 m, le marin (1,8 m) ne passe pas dessous.
    obstacle(-3f,1.45f,-2f,3.4f,1.65f,3.0f)                 // maison
    obstacle(-2f,3.2f,-2.6f,.5f,1.5f,.5f)                   // cheminée
    obstacle(4f,2.7f,-3f,1.4f,4.5f,1.4f)                    // phare
    for((x,z) in listOf(-5f to -1f,-6f to 3f,6f to 1f,1f to -5f,-3f to -4f))
        obstacle(x,1.45f,z,.40f,3f,.40f,camera=false)       // palmiers
    for((x,z) in listOf(-1.25f to -1.5f,-1.2f to -2.15f))
        obstacleAuSol(x,z,.55f,.75f,.55f,camera=false)      // tonneaux
    obstacleAuSol(-1.3f,-2.9f,.55f,.95f,.55f,camera=false)  // caisses empilées
    obstacleAuSol(-.75f,-3f,.4f,.4f,.4f,camera=false)       // petite caisse
    obstacleAuSol(-4f,-.35f,1f,.45f,.34f,camera=false)      // banc
    obstacleAuSol(4.6f,8.4f,.9f,.45f,.9f,camera=false)      // château de sable
    for((x,z) in listOf(-2f to -4.25f,5.5f to -4.6f,2.6f to 1.3f,-.3f to -3.7f,-6.4f to -1.8f,6.6f to .1f))
        obstacleAuSol(x,z,.9f,.65f,.8f,camera=false)        // buissons
    obstacle(-.85f,1.09f,12.35f,.2f,1.8f,.2f,camera=false)  // lanterne du ponton
    heros("models/marin.glb",x=-1f,y=2.2f,z=4f) {
        vitesse=4.4f
        impulsion=8f
    }
    for((x,z) in listOf(-4f to 4f,3f to 4f,7f to -2f,-7f to -3f,2f to -6f,-1.8f to 9.3f))
        coquillage(x,z)
    // Corps rigides : on peut les pousser, les faire tomber, taper dans le ballon.
    // Sur un endroit plat : le centre de l'île est bosselé, une pile y basculerait (vérifié).
    ballon(-1.5f,3.0f)
    for(k in 0..2) caisse(-2.4f,2.6f,hauteur=k*.72f,angle=k*.25f)  // une pile de trois caisses
    caisse(-3.4f,1.4f,taille=.55f,angle=.6f)
    caisse(4.2f,6.4f,angle=.3f); caisse(4.9f,6.9f,taille=.5f)    // sur la plage
    caisse(-1.4f,11.1f,taille=.6f,angle=.4f)                      // au bout du ponton
}
