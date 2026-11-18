package sillage.demo

import sillage.jeu

/** Le jeu tient dans ce fichier ; les détails Vulkan restent dans le moteur. */
fun main(args: Array<String>) = jeu("Sillage",args) {
    ocean()
    terrain("models/terrain.glb")
    modele("models/village.glb")
    // Modèles animés : les clips viennent des fichiers GLB.
    modele("models/palmiers.glb").animer("vent")
    modele("models/port.glb").animer("vie")
    modele("models/ciel.glb").animer("vol")
    // Volumes simples séparés du décor : modifier ces valeurs pour aménager l'île.
    obstacle(-3f,1.45f,-2f,2.7f,2.8f,2.4f)                  // maison
    obstacle(4f,2.7f,-3f,1.4f,4.5f,1.4f)                    // phare
    for((x,z) in listOf(-5f to -1f,-6f to 3f,6f to 1f,1f to -5f,-3f to -4f))
        obstacle(x,1.45f,z,.40f,3f,.40f)                    // palmiers
    for((x,z) in listOf(-1.25f to -1.5f,-1.2f to -2.15f))
        obstacleAuSol(x,z,.55f,.75f,.55f)                   // tonneaux
    obstacleAuSol(-1.3f,-2.9f,.55f,.95f,.55f)               // caisses empilées
    obstacleAuSol(-.75f,-3f,.4f,.4f,.4f)                    // petite caisse
    obstacleAuSol(-4f,-.35f,1f,.45f,.34f)                   // banc
    obstacleAuSol(4.6f,8.4f,.9f,.45f,.9f)                   // château de sable
    for((x,z) in listOf(-2f to -4.25f,5.5f to -4.6f,2.6f to 1.3f,-.3f to -3.7f,-6.4f to -1.8f,6.6f to .1f))
        obstacleAuSol(x,z,.9f,.65f,.8f)                     // buissons
    obstacle(-.85f,1.09f,12.35f,.2f,1.8f,.2f)               // lanterne du ponton
    heros("models/marin.glb",x=-1f,y=2.2f,z=4f) {
        vitesse=4.4f
        impulsion=8f
    }
    for((x,z) in listOf(-4f to 4f,3f to 4f,7f to -2f,-7f to -3f,2f to -6f,-1.8f to 9.3f))
        coquillage(x,z)
}
