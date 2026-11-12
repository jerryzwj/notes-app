package eu.wenjun.notes

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LoginScreen(vm: NotesViewModel) {
    var password by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(horizontal = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(R.drawable.ic_mark),
            contentDescription = null,
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(18.dp))
        Text("记事本", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text(
            "私人笔记 · 云端同步",
            fontSize = 13.sp,
            color = Muted,
            modifier = Modifier.padding(top = 6.dp)
        )

        Spacer(Modifier.height(30.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .background(Panel2, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(
                value = password,
                onValueChange = { password = it },
                singleLine = true,
                textStyle = TextStyle(color = Ink, fontSize = 16.sp),
                cursorBrush = SolidColor(Accent),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { vm.login(password) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                decorationBox = { inner ->
                    if (password.isEmpty()) {
                        Text("输入访问密码", color = Muted, fontSize = 16.sp)
                    }
                    inner()
                }
            )
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = { vm.login(password) },
            enabled = !vm.loading,
            shape = RoundedCornerShape(50.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Accent,
                contentColor = AccentInk,
                disabledContainerColor = Panel2,
                disabledContentColor = Muted
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Text(if (vm.loading) "登录中…" else "登录", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }

        Text(
            "登录一次即长期免密，改网页密码不影响这台手机",
            fontSize = 12.sp,
            color = Muted,
            modifier = Modifier.padding(top = 16.dp)
        )
    }
}
