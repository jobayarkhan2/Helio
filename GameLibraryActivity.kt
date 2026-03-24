package com.gamebooster.app

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.gamebooster.app.databinding.ActivityGameLibraryBinding
import kotlinx.coroutines.*

/**
 * GameLibraryActivity — Shows all detected games in a grid.
 *
 * Tapping a game:
 * 1. Triggers a quick RAM boost (kills background processes)
 * 2. Enables DND if permission granted
 * 3. Starts the BoostForegroundService (gaming session)
 * 4. Launches the game
 */
class GameLibraryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGameLibraryBinding
    private lateinit var gameScanner: GameScanner
    private lateinit var boostEngine: BoostEngine
    private lateinit var dndManager: DndManager
    private lateinit var adapter: GameAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGameLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        gameScanner = GameScanner(this)
        boostEngine = BoostEngine(this)
        dndManager = DndManager(this)

        setupRecyclerView()
        loadGames()
    }

    private fun setupRecyclerView() {
        adapter = GameAdapter { game -> onGameSelected(game) }
        binding.rvGames.layoutManager = GridLayoutManager(this, 3)
        binding.rvGames.adapter = adapter
    }

    private fun loadGames() {
        binding.progressLoading.visibility = View.VISIBLE
        binding.tvEmpty.visibility = View.GONE

        lifecycleScope.launch {
            val games = gameScanner.getInstalledGames()
            withContext(Dispatchers.Main) {
                binding.progressLoading.visibility = View.GONE
                if (games.isEmpty()) {
                    binding.tvEmpty.visibility = View.VISIBLE
                    binding.tvEmpty.text = "No games detected.\nInstall games from the Play Store."
                } else {
                    binding.tvGameCount.text = "${games.size} games found"
                    adapter.submitList(games)
                }
            }
        }
    }

    private fun onGameSelected(game: GameInfo) {
        binding.tvLaunching.visibility = View.VISIBLE
        binding.tvLaunching.text = "Boosting for ${game.appName}..."

        lifecycleScope.launch {
            // Step 1: Quick RAM boost before launching
            boostEngine.boost()

            withContext(Dispatchers.Main) {
                // Step 2: Enable DND
                if (dndManager.hasDndPermission()) {
                    dndManager.enableGamingDnd()
                }

                // Step 3: Start gaming session service
                BoostForegroundService.start(this@GameLibraryActivity)

                // Step 4: Launch the game
                val launched = gameScanner.launchGame(game.packageName)
                binding.tvLaunching.visibility = View.GONE

                if (!launched) {
                    Toast.makeText(
                        this@GameLibraryActivity,
                        "Could not launch ${game.appName}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
}
