package io.nekohasekai.sagernet.ui

import androidx.recyclerview.widget.DiffUtil

/** Immutable input only: callers calculate on Default, publish list and updates together on Main. */
internal object NodeListDiff {
    fun calculate(oldIds: List<Long>, newIds: List<Long>, changedIds: Set<Long>): DiffUtil.DiffResult =
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldIds.size
            override fun getNewListSize() = newIds.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                oldIds[oldItemPosition] == newIds[newItemPosition]
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                newIds[newItemPosition] !in changedIds
        }, oldIds.size <= 1000 && newIds.size <= 1000)
}
