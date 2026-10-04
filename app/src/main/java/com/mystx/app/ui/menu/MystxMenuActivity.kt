package com.mystx.app.ui.menu

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mystx.app.ui.processtext.ProcessTextActivity
import com.mystx.app.ui.processtext.QuickExplainActivity
import com.mystx.app.ui.theme.MystxTheme

class MystxMenuActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val textToProcess = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)

        setContent {
            MystxTheme {
                Surface(
                    modifier = Modifier.padding(16.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        MenuItem(
                            text = "Command",
                            onClick = {
                                val intent = Intent(this@MystxMenuActivity, ProcessTextActivity::class.java).apply {
                                    if (textToProcess != null) putExtra(Intent.EXTRA_PROCESS_TEXT, textToProcess)
                                    putExtra("from_assistant", true)
                                }
                                startActivity(intent)
                                finish()
                            }
                        )
                        MenuItem(
                            text = "Explain",
                            onClick = {
                                val intent = Intent(this@MystxMenuActivity, QuickExplainActivity::class.java).apply {
                                    if (textToProcess != null) putExtra(Intent.EXTRA_PROCESS_TEXT, textToProcess)
                                }
                                startActivity(intent)
                                finish()
                            }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun MenuItem(text: String, onClick: () -> Unit) {
        Text(
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(16.dp),
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
