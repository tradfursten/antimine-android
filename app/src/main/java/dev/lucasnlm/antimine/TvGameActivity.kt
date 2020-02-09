package dev.lucasnlm.antimine

import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.text.format.DateUtils
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.os.HandlerCompat
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import androidx.preference.PreferenceManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import dagger.android.support.DaggerAppCompatActivity
import dev.lucasnlm.antimine.about.AboutActivity
import dev.lucasnlm.antimine.common.level.data.DifficultyPreset
import dev.lucasnlm.antimine.common.level.data.GameEvent
import dev.lucasnlm.antimine.common.level.data.GameStatus
import dev.lucasnlm.antimine.common.level.viewmodel.GameViewModel
import dev.lucasnlm.antimine.common.level.viewmodel.GameViewModelFactory
import dev.lucasnlm.antimine.core.preferences.IPreferencesRepository
import dev.lucasnlm.antimine.level.view.CustomLevelDialogFragment
import dev.lucasnlm.antimine.level.view.LevelFragment
import dev.lucasnlm.antimine.preferences.PreferencesActivity
import kotlinx.android.synthetic.main.activity_tv_game.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import javax.inject.Inject

class TvGameActivity : DaggerAppCompatActivity() {

    @Inject
    lateinit var viewModelFactory: GameViewModelFactory

    @Inject
    lateinit var preferencesRepository: IPreferencesRepository

    private lateinit var viewModel: GameViewModel

    private var gameStatus: GameStatus = GameStatus.PreGame

    private var keepConfirmingNewGame = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv_game)
        viewModel = ViewModelProviders.of(this, viewModelFactory).get(GameViewModel::class.java)
        bindViewModel()

        PreferenceManager.setDefaultValues(this, R.xml.preferences, false)

        loadGameFragment()

        if (Build.VERSION.SDK_INT >= 21) {
            checkUpdate()
        }
    }

    private fun bindViewModel() = viewModel.apply {
        eventObserver.observe(this@TvGameActivity, Observer {
            onGameEvent(it)
        })
        elapsedTimeSeconds.observe(this@TvGameActivity, Observer {
            timer.apply {
                visibility = if (it == 0L) View.GONE else View.VISIBLE
                text = DateUtils.formatElapsedTime(it)
            }
        })
        mineCount.observe(this@TvGameActivity, Observer {
            minesCount.apply {
                visibility = View.VISIBLE
                text = it.toString()
            }
        })
        difficulty.observe(this@TvGameActivity, Observer {
            //onChangeDifficulty(it)
        })
    }

    override fun onResume() {
        super.onResume()
        if (gameStatus == GameStatus.Running) {
            viewModel.resumeGame()
        }
    }

    override fun onPause() {
        super.onPause()

        if (gameStatus == GameStatus.Running) {
            viewModel.pauseGame()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean =
        when (gameStatus) {
            GameStatus.Over, GameStatus.Running -> {
                menuInflater.inflate(R.menu.top_menu_over, menu)
                true
            }
            else -> true
        }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == R.id.reset) {
            if (gameStatus == GameStatus.Running) {
                newGameConfirmation {
                    GlobalScope.launch {
                        viewModel.startNewGame()
                    }
                }
            } else {
                GlobalScope.launch {
                    viewModel.startNewGame()
                }
            }
            true
        } else {
            super.onOptionsItemSelected(item)
        }
    }

    private fun loadGameFragment() {
        val fragmentManager = supportFragmentManager

        fragmentManager.popBackStack()

        fragmentManager.findFragmentById(R.id.levelContainer)?.let { it ->
            fragmentManager.beginTransaction().apply {
                remove(it)
                commitAllowingStateLoss()
            }
        }

        fragmentManager.beginTransaction().apply {
            replace(R.id.levelContainer, LevelFragment())
            setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
            commitAllowingStateLoss()
        }
    }

    private fun newGameConfirmation(action: () -> Unit) {
        AlertDialog.Builder(this, R.style.MyDialog).apply {
            setTitle(R.string.start_over)
            setMessage(R.string.retry_sure)
            setPositiveButton(R.string.resume) { _, _ -> action() }
            setNegativeButton(R.string.cancel, null)
            show()
        }
    }

    private fun showQuitConfirmation(action: () -> Unit) {
        AlertDialog.Builder(this, R.style.MyDialog)
            .setTitle(R.string.are_you_sure)
            .setMessage(R.string.sure_quit_desc)
            .setPositiveButton(R.string.quit) { _, _ -> action() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showCustomLevelDialog() {
        CustomLevelDialogFragment().apply {
            show(supportFragmentManager, "custom_level_fragment")
        }
    }

    private fun showAbout() {
        Intent(this, AboutActivity::class.java).apply {
            startActivity(this)
        }
    }

    private fun showSettings() {
        Intent(this, PreferencesActivity::class.java).apply {
            startActivity(this)
        }
    }

    private fun showVictory() {
        AlertDialog.Builder(this, R.style.MyDialog).apply {
            setTitle(R.string.you_won)
            setMessage(R.string.all_mines_disabled)
            setCancelable(false)
            setPositiveButton(R.string.new_game) { _, _ ->
                GlobalScope.launch {
                    viewModel.startNewGame()
                }
            }
            setNegativeButton(R.string.cancel, null)
            show()
        }
    }

    private fun waitAndShowConfirmNewGame() {
        if (keepConfirmingNewGame) {
            HandlerCompat.postDelayed(Handler(), {
                if (this.gameStatus == GameStatus.Over && !isFinishing) {
                    AlertDialog.Builder(this, R.style.MyDialog).apply {
                        setTitle(R.string.new_game)
                        setMessage(R.string.new_game_request)
                        setPositiveButton(R.string.yes) { _, _ ->
                            GlobalScope.launch {
                                viewModel.startNewGame()
                            }
                        }
                        setNegativeButton(R.string.cancel, null)
                    }.show()

                    keepConfirmingNewGame = false
                }
            }, null, DateUtils.SECOND_IN_MILLIS)
        }
    }

    private fun waitAndShowGameOverConfirmNewGame() {
        HandlerCompat.postDelayed(Handler(), {
            if (this.gameStatus == GameStatus.Over && !isFinishing) {
                AlertDialog.Builder(this, R.style.MyDialog).apply {
                    setTitle(R.string.you_lost)
                    setMessage(R.string.new_game_request)
                    setPositiveButton(R.string.yes) { _, _ ->
                        GlobalScope.launch {
                            viewModel.startNewGame()
                        }
                    }
                    setNegativeButton(R.string.cancel, null)
                }.show()
            }
        }, null, DateUtils.SECOND_IN_MILLIS)
    }

    private fun changeDifficulty(newDifficulty: DifficultyPreset) {
        if (gameStatus == GameStatus.PreGame) {
            GlobalScope.launch {
                viewModel.startNewGame(newDifficulty)
            }
        } else {
            newGameConfirmation {
                GlobalScope.launch {
                    viewModel.startNewGame(newDifficulty)
                }
            }
        }
    }

    private fun onGameEvent(event: GameEvent) {
        when (event) {
            GameEvent.ResumeGame -> {
                invalidateOptionsMenu()
            }
            GameEvent.StartNewGame -> {
                gameStatus = GameStatus.PreGame
                invalidateOptionsMenu()
            }
            GameEvent.Resume, GameEvent.Running -> {
                gameStatus = GameStatus.Running
                viewModel.runClock()
                invalidateOptionsMenu()
            }
            GameEvent.Victory -> {
                gameStatus = GameStatus.Over
                viewModel.stopClock()
                viewModel.revealAllEmptyAreas()
                invalidateOptionsMenu()
                showVictory()
            }
            GameEvent.GameOver -> {
                gameStatus = GameStatus.Over
                invalidateOptionsMenu()
                viewModel.stopClock()
                viewModel.gameOver()

                waitAndShowGameOverConfirmNewGame()
            }
            GameEvent.ResumeVictory, GameEvent.ResumeGameOver -> {
                gameStatus = GameStatus.Over
                invalidateOptionsMenu()
                viewModel.stopClock()

                waitAndShowConfirmNewGame()
            }
            else -> {

            }
        }
    }

    private fun checkUpdate() {
        val appUpdateManager = AppUpdateManagerFactory.create(this)
        val appUpdateInfoTask = appUpdateManager.appUpdateInfo

        appUpdateInfoTask.addOnSuccessListener { info ->
            if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                && info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) {
                try {
                    appUpdateManager.startUpdateFlowForResult(
                        info, AppUpdateType.FLEXIBLE, this, 1)
                } catch (e: IntentSender.SendIntentException) {
                    Log.e(TAG, "Fail to request update.")
                }
            }
        }

    }

    companion object {
        const val TAG = "GameActivity"
        const val PREFERENCE_FIRST_USE = "preference_first_use"
    }
}