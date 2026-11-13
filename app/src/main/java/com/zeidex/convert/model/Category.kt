package com.zeidex.convert.model

import com.zeidex.convert.R

enum class Category(val id: String, val labelRes: Int) {
    IMAGE("image", R.string.category_image),
    VIDEO("video", R.string.category_video),
    AUDIO("audio", R.string.category_audio),
    PDF("pdf", R.string.category_pdf),
    DOCUMENT("document", R.string.category_document),
    SUBTITLE("subtitle", R.string.category_subtitle),
    ARCHIVE("archive", R.string.category_archive)
}
