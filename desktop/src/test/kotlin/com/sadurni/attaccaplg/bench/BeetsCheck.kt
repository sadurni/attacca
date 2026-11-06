package com.sadurni.attaccaplg.bench

import com.sadurni.attaccaplg.beets.Beets
import com.sadurni.attaccaplg.generate.Library
import com.sadurni.attaccaplg.library.LibraryDb
import java.io.File

/** Development: finds beets (its config from BEETSDIR) and writes the library's measures into it. Args: db|beet. */
fun main(args: Array<String>) {
    val library = Library(LibraryDb(File(args[0])))
    val setup = Beets.find(args.getOrNull(1)) ?: error("no beets")
    println("beets ${setup.command}  library ${setup.library}  music ${setup.directory}")
    println("written: " + Beets.writeAttributes(setup.library!!, library))
    File(args[0] + ".beets.txt").writeText(Beets.run(setup.command, listOf("ls", "-f", "\$attacca_mood \$attacca_family \$attacca_bpm \$attacca_energy \$attacca_positivity  \$title")) ?: "")
}
