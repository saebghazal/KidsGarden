package com.kidsgarden.app.ui.setup

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.kidsgarden.app.R

/** First setup step (T034). Shown only while no parent PIN exists. */
@Composable
fun WelcomeScreen(onContinue: () -> Unit) {
    SetupScaffold(
        title = stringResource(R.string.welcome_title),
        actions = {
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.welcome_start))
            }
        },
    ) {
        BodyText(stringResource(R.string.welcome_body))
    }
}
