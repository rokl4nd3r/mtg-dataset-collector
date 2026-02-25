package com.example.mtgdatasetcollector.util

import android.content.Context
import java.io.File
import java.io.IOException

object StoragePaths {

    fun stagingRoot(context: Context): File =
        File(context.filesDir, AppConfig.STAGING_DIR)

    fun imagesDir(context: Context): File =
        File(stagingRoot(context), AppConfig.IMAGES_DIR)

    fun metaDir(context: Context): File =
        File(stagingRoot(context), AppConfig.META_DIR)

    fun ensureDirs(context: Context) {
        val dirs = listOf(stagingRoot(context), imagesDir(context), metaDir(context))
        for (d in dirs) {
            if (!d.exists() && !d.mkdirs()) {
                throw IOException("Failed to create dir: ${d.absolutePath}")
            }
        }
    }

    /**
     * NOVO: cria arquivo a partir de um baseId (o mesmo para FRONT e BACK da carta).
     */
    fun newStagingImageFile(context: Context, baseId: String, side: String): File {
        ensureDirs(context)
        val name = IdGenerator.imageFileNameFromBase(baseId, side = side, ext = "jpg")
        return File(imagesDir(context), name)
    }

    /**
     * Compat: gera um baseId novo a cada chamada (evite se quiser par consistente).
     */
    fun newStagingImageFile(context: Context, side: String): File {
        ensureDirs(context)
        val name = IdGenerator.nextImageFileName(context, side = side, ext = "jpg")
        return File(imagesDir(context), name)
    }

    fun newStagingMetaFile(context: Context, baseName: String): File {
        ensureDirs(context)
        // baseName sem extensão (ex: ..._front)
        return File(metaDir(context), "$baseName.json")
    }
}