/*
 *
 * Nextcloud Android client application
 *
 * @author Tobias Kaminsky
 * Copyright (C) 2022 Tobias Kaminsky
 * Copyright (C) 2022 Nextcloud GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.owncloud.android.ui.adapter

import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.elyeproj.loaderviewlibrary.LoaderImageView
import com.owncloud.android.databinding.GridItemBinding

internal class FolderGridViewHolder(var binding: GridItemBinding) :
    RecyclerView.ViewHolder(
        binding.root
    ),
    ListGridItemViewHolder {
    override val fileFeaturesLayout: LinearLayout
        get() = binding.fileFeaturesLayout
    override val fileName: TextView
        get() = binding.Filename
    override val thumbnail: ImageView
        get() = binding.thumbnail

    override fun showVideoOverlay() {
        binding.videoOverlay.visibility = View.VISIBLE
    }

    override val more: ImageButton
        get() = binding.more
    override val shimmerThumbnail: LoaderImageView
        get() = binding.thumbnailShimmer
    override val favorite: ImageView
        get() = binding.favoriteAction
    override val localFileIndicator: ImageView
        get() = binding.localFileIndicator
    override val shared: ImageView
        get() = binding.sharedIcon
    override val checkbox: ImageView
        get() = binding.customCheckbox
    override val itemLayout: View
        get() = binding.ListItemLayout
    override val unreadComments: ImageView
        get() = binding.unreadComments

    override val gridLivePhotoIndicator: TextView?
        get() = null
    override val livePhotoIndicator: TextView?
        get() = null
    override val livePhotoIndicatorSeparator: TextView?
        get() = null

    init {
        binding.favoriteAction.drawable.mutate()
    }

    override fun checkVisibilityOfFileFeaturesLayout() {
        if (favorite.visibility == View.GONE &&
            binding.videoOverlay.visibility == View.GONE &&
            shared.visibility == View.GONE &&
            unreadComments.visibility == View.GONE &&
            localFileIndicator.visibility == View.GONE &&
            checkbox.visibility == View.GONE) {
            fileFeaturesLayout.visibility = View.GONE
        } else {
            fileFeaturesLayout.visibility = View.VISIBLE
        }
    }
}
