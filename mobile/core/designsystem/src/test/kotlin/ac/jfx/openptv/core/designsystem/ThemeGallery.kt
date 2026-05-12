/*
 * Copyright 2026 OpenPTV contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ac.jfx.openptv.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The visual contract for `:core:designsystem`. Every theme-relevant token
 * (background, primary, on-primary, secondary container, on-surface) is
 * touched by at least one widget in this gallery so a colour drift in
 * `OpenPtvTheme` produces a pixel diff on at least one cell.
 *
 * Intentionally compact and feature-free — this is the design-system
 * baseline, not an end-to-end screen. Feature modules contribute their own
 * smoke screenshots in later phases.
 */
@Composable
fun ThemeGallery() {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "OpenPTV theme gallery",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Button(onClick = {}) {
                Text("Primary button")
            }
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Card title",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Body text inside an M3 Card — exercises onSurface tokens.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            ListItem(
                headlineContent = { Text("Flinders Street") },
                supportingContent = { Text("Platform 4 — 3 minutes") },
                trailingContent = { Text("3m") },
            )
            ExtendedFloatingActionButton(
                onClick = {},
                text = { Text("Departures") },
                icon = {},
            )
            // Padding cell so the FAB doesn't sit flush against the bottom on
            // shorter device qualifiers — keeps the layout from looking
            // cropped in the screenshot.
            Spacer(modifier = Modifier.padding(PaddingValues(bottom = 8.dp)))
        }
    }
}
