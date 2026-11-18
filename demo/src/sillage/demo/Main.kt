package sillage.demo

import sillage.jeu

/** Le jeu tient dans ce fichier ; les détails Vulkan restent dans le moteur. */
fun main(args: Array<String>) = jeu("Sillage",args) {
    ocean()
    terrain("models/terrain.glb")
    modele("models/village.glb")
    // Volumes simples séparés du décor : modifier ces valeurs pour aménager l'île.
    obstacle(-3f,1.45f,-2f,2.7f,2.8f,2.4f)
    obstacle(4f,2.7f,-3f,1.4f,4.5f,1.4f)
    for((x,z) in listOf(-5f to -1f,-6f to 3f,6f to 1f,1f to -5f,-3f to -4f))
        obstacle(x,1.45f,z,.40f,3f,.40f)
    heros("models/marin.glb",x=-1f,y=2.2f,z=4f) {
        vitesse=4.4f
        impulsion=8f
    }
    for((x,z) in listOf(-4f to 4f,3f to 4f,7f to -2f,-7f to -3f,2f to -6f,-1.8f to 9.3f))
        coquillage(x,z)
}
