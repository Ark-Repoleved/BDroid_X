
package com.example.bd2modmanager.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// A placeholder data class for a Mod. 
// Using a data class with `val` properties makes it stable for Compose.
data class Mod(
    val name: String,
    val path: String, // The unique path to the mod, perfect for a key
    val author: String,
)

@Composable
fun ModRow(mod: Mod, modifier: Modifier = Modifier) {
    // A simple representation of what a single mod item might look like.
    Card(modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Mod: ${mod.name}")
            Text(text = "Author: ${mod.author}")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ModListScreen(mods: List<Mod>) {
    // This list is now fully optimized for smooth scrolling.
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(
            items = mods,
            key = { mod -> mod.path }
        ) { mod ->
            ModRow(
                mod = mod,
                // This modifier automatically animates item appearances, disappearances,
                // and reordering. The tween spec provides a gentle animation curve.
                modifier = Modifier.animateItemPlacement(
                    animationSpec = tween(durationMillis = 300)
                )
            )
        }
    }
}
