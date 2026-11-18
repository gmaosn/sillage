package sillage

import sillage.math.Vec3

/** Police bitmap dessinée par Vulkan, sans toolkit UI ni texture. */
internal object Hud {
    private val glyphs = mapOf(
        'A' to "01110/10001/10001/11111/10001/10001/10001", 'B' to "11110/10001/10001/11110/10001/10001/11110",
        'C' to "01111/10000/10000/10000/10000/10000/01111", 'D' to "11110/10001/10001/10001/10001/10001/11110",
        'E' to "11111/10000/10000/11110/10000/10000/11111", 'F' to "11111/10000/10000/11110/10000/10000/10000",
        'G' to "01111/10000/10000/10111/10001/10001/01111", 'H' to "10001/10001/10001/11111/10001/10001/10001",
        'I' to "11111/00100/00100/00100/00100/00100/11111", 'J' to "00111/00010/00010/00010/10010/10010/01100",
        'K' to "10001/10010/10100/11000/10100/10010/10001", 'L' to "10000/10000/10000/10000/10000/10000/11111",
        'M' to "10001/11011/10101/10101/10001/10001/10001", 'N' to "10001/11001/10101/10011/10001/10001/10001",
        'O' to "01110/10001/10001/10001/10001/10001/01110", 'P' to "11110/10001/10001/11110/10000/10000/10000",
        'Q' to "01110/10001/10001/10001/10101/10010/01101", 'R' to "11110/10001/10001/11110/10100/10010/10001",
        'S' to "01111/10000/10000/01110/00001/00001/11110", 'T' to "11111/00100/00100/00100/00100/00100/00100",
        'U' to "10001/10001/10001/10001/10001/10001/01110", 'V' to "10001/10001/10001/10001/10001/01010/00100",
        'W' to "10001/10001/10001/10101/10101/10101/01010", 'X' to "10001/10001/01010/00100/01010/10001/10001",
        'Y' to "10001/10001/01010/00100/00100/00100/00100", 'Z' to "11111/00001/00010/00100/01000/10000/11111",
        '0' to "01110/10001/10011/10101/11001/10001/01110", '1' to "00100/01100/00100/00100/00100/00100/01110",
        '2' to "01110/10001/00001/00010/00100/01000/11111", '3' to "11110/00001/00001/01110/00001/00001/11110",
        '4' to "00010/00110/01010/10010/11111/00010/00010", '5' to "11111/10000/10000/11110/00001/00001/11110",
        '6' to "01110/10000/10000/11110/10001/10001/01110", '7' to "11111/00001/00010/00100/01000/01000/01000",
        '8' to "01110/10001/10001/01110/10001/10001/01110", '9' to "01110/10001/10001/01111/00001/00001/01110",
        '/' to "00001/00010/00010/00100/01000/01000/10000", '-' to "00000/00000/00000/11111/00000/00000/00000",
        ':' to "00000/00100/00100/00000/00100/00100/00000", '.' to "00000/00000/00000/00000/00000/00100/00100"
    ).mapValues { it.value.split('/') }

    fun construire(monde: Monde, fps: Int, captureSouris: Boolean): Maillage {
        val g=Geometrie()
        val ink=Couleur.srgb(0x153d48); val paper=Couleur.srgb(0xffedbe); val gold=Couleur.srgb(0xffce6e)
        fun rect(x: Float,y: Float,w: Float,h: Float,c: Couleur) {
            fun p(px: Float,py: Float)=Vec3(px/480f-1f,py/300f-1f,0f)
            g.quad(p(x,y),p(x+w,y),p(x+w,y+h),p(x,y+h),c)
        }
        fun text(t: String,x: Float,y: Float,scale: Float,c: Couleur) {
            t.forEachIndexed { i,ch -> glyphs[ch]?.forEachIndexed { row,line -> line.forEachIndexed { col,pixel ->
                if(pixel=='1') rect(x+(i*6+col)*scale,y+row*scale,scale,scale,c)
            } } }
        }
        rect(24f,24f,250f,82f,ink); rect(24f,24f,4f,82f,gold)
        text("SILLAGE",42f,38f,3f,paper)
        text("LES ALIZES",42f,73f,2f,gold)
        rect(686f,24f,250f,82f,ink)
        text("COQUILLAGES",704f,38f,2f,paper)
        text(monde.recoltes.toString()+" / "+monde.coquillages.size,704f,73f,2f,gold)
        rect(24f,528f,912f,48f,ink)
        text("ZQSD / WASD  BOUGER    MAJ  MARCHER    ESPACE  SAUTER    SOURIS  CAMERA",42f,540f,1.5f,paper)
        text("MOLETTE  ZOOM    M  SON    R  RECOMMENCER    TAB  SOURIS    ECHAP  QUITTER",42f,558f,1.25f,gold)
        text(fps.toString()+" FPS",836f,118f,1.25f,ink)
        if(monde.recoltes==monde.coquillages.size && monde.coquillages.isNotEmpty()) {
            rect(220f,190f,520f,84f,ink)
            text("TRESOR DES ALIZES",244f,208f,3f,gold)
            text("TOUS LES COQUILLAGES SONT RETROUVES",244f,248f,1.5f,paper)
        } else if(!captureSouris) {
            rect(320f,478f,320f,26f,ink)
            text("TAB POUR CAPTURER LA SOURIS",334f,486f,1.5f,paper)
        }
        val mesh=g.construire()
        for(i in mesh.sommets.indices step 9) { mesh.sommets[i+3]=0f; mesh.sommets[i+4]=0f; mesh.sommets[i+5]=0f }
        return mesh
    }
}
