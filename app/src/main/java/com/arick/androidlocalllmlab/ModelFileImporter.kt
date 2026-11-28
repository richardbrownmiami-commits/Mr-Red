package com.arick.androidlocalllmlab
import android.content.Context
import android.net.Uri
import java.io.File
object ModelFileImporter { fun import(context:Context,uri:Uri):File { val dir=File(context.filesDir,"models").apply{mkdirs()};val file=File(dir,(uri.lastPathSegment?.substringAfterLast('/')?: "model.gguf").replace(Regex("[^A-Za-z0-9._-]"),"_"));context.contentResolver.openInputStream(uri).use{input->requireNotNull(input);input.copyTo(file.outputStream())};require(file.length()>0);return file } }