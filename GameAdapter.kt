package com.gamebooster.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.gamebooster.app.databinding.ItemGameBinding

class GameAdapter(
    private val onGameClick: (GameInfo) -> Unit
) : ListAdapter<GameInfo, GameAdapter.GameViewHolder>(GameDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GameViewHolder {
        val binding = ItemGameBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return GameViewHolder(binding)
    }

    override fun onBindViewHolder(holder: GameViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class GameViewHolder(private val binding: ItemGameBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(game: GameInfo) {
            binding.tvGameName.text = game.appName
            binding.ivGameIcon.setImageDrawable(game.icon)
            binding.root.setOnClickListener { onGameClick(game) }
        }
    }

    class GameDiffCallback : DiffUtil.ItemCallback<GameInfo>() {
        override fun areItemsTheSame(oldItem: GameInfo, newItem: GameInfo) =
            oldItem.packageName == newItem.packageName

        override fun areContentsTheSame(oldItem: GameInfo, newItem: GameInfo) =
            oldItem.appName == newItem.appName
    }
}
