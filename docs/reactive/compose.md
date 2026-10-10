# Jetpack Compose UI Binding Reference

Because the Aduki Android SDK exposes standard Kotlin `StateFlow` primitives backed by ObjectBox live queries, integrating with Jetpack Compose needs no adapter code: collect the flows with `collectAsStateWithLifecycle()`.

---

## 1. ViewModel Implementation

```kotlin
package com.example.adukiapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import pro.aduki.sdk.Aduki
import pro.aduki.store.entities.Message

class MailboxViewModel(
    private val client: Aduki,
    private val mailboxHex: String = "inbox"
) : ViewModel() {

    // StateFlow fed by an ObjectBox query observer
    val messages: StateFlow<List<Message>> = client.mail
        .observe(mailboxHex) ?: MutableStateFlow(emptyList())

    val unread: StateFlow<Int> = client.mail
        .unread(mailboxHex) ?: MutableStateFlow(0)

    fun toggleFlag(hex: String) {
        viewModelScope.launch {
            client.mail.flag(hex, Message.FLAGGED)
        }
    }

    fun markSeen(hex: String) {
        viewModelScope.launch {
            client.mail.flag(hex, Message.SEEN)
        }
    }

    fun delete(hex: String) {
        viewModelScope.launch {
            client.mail.remove(hex)
        }
    }
}
```

---

## 2. High-Performance Composable List

```kotlin
package com.example.adukiapp.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailboxScreen(viewModel: MailboxViewModel) {
    // Collect lifecycle-aware: pauses collection when Activity/Fragment is stopped
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val unreadCount by viewModel.unread.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Inbox ($unreadCount unread)") })
        }
    ) { paddingValues ->
        LazyColumn(modifier = Modifier.padding(paddingValues)) {
            // Stable keying: 'key = { it.hex }' prevents whole-list recomposition on flag update
            items(
                items = messages,
                key = { message -> message.hex }
            ) { message ->
                MessageItemRow(
                    message = message,
                    onFlagClicked = { viewModel.toggleFlag(message.hex) },
                    onItemClicked = { viewModel.markSeen(message.hex) }
                )
            }
        }
    }
}
```

---

## 3. Recomposition Best Practices

1. **Always Supply `key = { it.hex }`**: Without a stable key, inserting a single message at index 0 forces Compose to recreate all visible rows. With `key = { it.hex }`, Compose can keep the other rows and animate the insertion.
2. **Use `collectAsStateWithLifecycle()`**: Stops collecting while the screen is in the background.
3. **Immutability**: Each emission is a freshly read list; treat the entities in it as read-only.

