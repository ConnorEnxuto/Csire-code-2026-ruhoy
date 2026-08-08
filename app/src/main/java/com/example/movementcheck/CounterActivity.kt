package com.example.movementcheck

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Standalone second version of the app: the original placeholder behavior, restored
 * unchanged and kept separate from [MainActivity] — an auto-incrementing counter
 * (+1 every second), displayed binocularly (same content duplicated into two side-by-side
 * panes for the glasses' stereo optics). Registered as its own launcher entry in the
 * manifest so it runs independently of the CSV rep-detection screen.
 */
class CounterActivity : ComponentActivity() {

    private val counterState = CounterStateHolder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            CounterApp(counterState)
        }

        // ---- PLACEHOLDER ONLY ----
        // Increments the counter by 1 every second.
        lifecycleScope.launch {
            while (true) {
                delay(1000)
                counterState.increment()
            }
        }
    }
}

class CounterStateHolder {
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count

    fun increment() {
        _count.value = _count.value + 1
    }

    fun update(value: Int) {
        _count.value = value
    }
}

@Composable
fun CounterApp(stateHolder: CounterStateHolder) {
    val count by stateHolder.count.collectAsStateWithLifecycle()

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black,
        ) {
            // Row lays its children left-to-right. Each child gets
            // weight(1f), meaning the available width is split evenly —
            // two equal halves, one per eye.
            Row(modifier = Modifier.fillMaxSize()) {
                CounterEyePane(count = count, modifier = Modifier.weight(1f).fillMaxHeight())
                CounterEyePane(count = count, modifier = Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

/**
 * One eye's copy of the content. Identical for both eyes — if you later need slightly
 * different horizontal offsets per eye to match the glasses' actual lens spacing/
 * convergence, that adjustment goes here.
 */
@Composable
fun CounterEyePane(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = count.toString(),
            color = Color.White,
            fontSize = 100.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
