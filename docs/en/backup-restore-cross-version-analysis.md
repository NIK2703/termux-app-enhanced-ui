# Сценарий 7: Cross-version Restore

> Анализ рисков и рекомендации для post-restore действий после восстановления архива $PREFIX, сделанного в другой версии Termux (другой NDK API level, libc, lib, версия пакетов).

## Предпосылки

Текущая реализация `TermuxBackupUtils.restore()`:
- Архив содержит **весь** `$FILES` (`$PREFIX` + `$HOME`)
- Restore делает полный wipe `files/` → распаковывает tar как есть
- **Нет post-restore действий** — только `listener.onResult(null)` при успехе
- В UI: Toast "Data restored successfully"

---

## 1. Корень проблемы: dpkg status не соответствует repo

### Что происходит при restore

Архив содержит:
- `usr/var/lib/dpkg/status` — БД установленных пакетов с версиями на момент backup
- `usr/var/lib/apt/lists/*` — кеш repo-индексов
- `usr/var/lib/dpkg/available` — список доступных пакетов
- `usr/etc/apt/sources.list` — список репозиториев (может быть stale mirror)

После restore эти файлы заменяются архивными. dpkg «думает», что установлены старые версии A.B.C, а репозиторий уже содержит X.Y.Z с другим ABI, другими зависимостями и другим списком файлов.

### Цепочка отказов

```
Архив 2-месячной давности на свежем Termux
  → dpkg status: libandroid-support 28 → repo имеет libandroid-support 30
  → libandroid-support 30 требует более новый libc++_shared.so
  → apt upgrade скачивает libc++... но она сконфигурирована для NEW ABI
  → ld.so не может загрузить старые бинарники в $PREFIX/bin/
  → bash не стартует → shell мёртв
```

### Конкретные сбои

| Сценарий | Симптом | Вероятность |
|----------|---------|------------|
| Файл переименован в новой версии пакета | `dpkg: error: conflicting files` | Высокая |
| Pre/postinst скрипты вызывают устаревшие команды | `postinst: command not found` | Средняя |
| Пакет удалён из репозитория | `E: Unable to fetch 404` | Низкая |
| GPG-ключ termux-keyring обновился | `NO_PUBKEY` / `RELEASE_NOT_SIGNED` | Средняя |
| SONAME библиотеки изменился + старый .so | SEGFAULT, `CANNOT LINK EXECUTABLE` | Высокая |

---

## 2. Анализ вариантов размещения рекомендации

### Вариант (a): Toast в UI после успешного restore

**Текущий код** (`TermuxPreferencesFragment.java:201-203`):
```java
if (error == null) {
    android.widget.Toast.makeText(activity, getString(successMsgRes),
        android.widget.Toast.LENGTH_LONG).show();
}
```

**Оценка:** ❌ **Недостаточно.**

- Toast живёт ~3.5 секунды (LENGTH_LONG)
- Нельзя скопировать команду из Toast
- Пользователь может не успеть прочитать
- Toast не позволяет встроить форматированный текст с командами

**Улучшение:** ✅ **Заменить на AlertDialog** после успешного restore (НЕ backup). Диалог:
- Заголовок: "Restore Complete"
- Сообщение: объяснение + точные команды `apt update && apt upgrade`
- Кнопки: "Open Termux" + "Got it"
- При нажатии "Open Termux" — открыть `TermuxActivity`

**Плюсы:**
- Persistent до dismiss
- Чёткое, читаемое сообщение с командами
- Можно добавить action (открыть Termux)
- Минимальные изменения кода
- Низкий риск — не блокирует, не требует действий

**Минусы:**
- Одноразовое уведомление — если dismiss, больше не покажется
- Нужно различать backup/restore в `runWithProgress()`

### Вариант (b): В документации

**Текущее состояние:** Частично уже есть в `docs/en/backup-restore-cross-version-analysis.md`.

**Оценка:** ✅ **Необходимо, но недостаточно.**

- Документация — правильное место для полного объяснения рисков
- Нужна для advanced users и тех, кто читает README/docs
- **Не охватывает** пользователей, которые нажимают "Restore" без подготовки

**Улучшение:**
- Финализировать существующий документ
- Добавить ссылку на документацию из UI (например, в диалоге restore warning)

### Вариант (c): Автоматически запустить apt update

**Оценка:** ❌ **Невозможно и нежелательно.**

**Почему невозможно:**
1. **Нет shell во время SAF restore.** Restore работает из Java Activity (не terminal session). Создание shell через `Runtime.exec()` после tar:
   - termux-exec LD_PRELOAD может быть битым → `Runtime.exec()` падает
   - Путь к `apt` может отсутствовать (tar ещё не распаковал `$PREFIX/bin/apt`)
2. **apt может не запуститься.** Если `libtermux-exec-*.so` из архива несовместим с новым APK, все бинарники падают с `CANNOT LINK EXECUTABLE` — `apt update` невозможен.
3. **Нет гарантии сети.** SAF restore может работать офлайн.
4. **Блокировка dpkg.** Если кто-то другой (termux-auto-update) держит lock, `apt` зависнет.
5. **Кривая ошибок.** Если `apt update` падает с ошибкой (NO_PUBKEY, mirror 404), пользователь видит "Restore failed" — неправильно.

**Почему нежелательно:**
- Restore и package management — **ортогональные операции**. Restore = файловая операция. Package management = пользовательская. Смешивание усложняет код и UX.
- apt update может занять минуты (download индексов) — прогресс-бар restore будет висеть, пользователь не понимает что происходит.

---

## 3. Рекомендация

### Первично: AlertDialog после успешного restore (вместо Toast)

**Где:** `TermuxPreferencesFragment.java`, метод `runWithProgress()`

**Что изменить:**

```java
// Вместо Toast для restore — показывать диалог с рекомендацией
if (error == null) {
    if (/* это restore */) {
        new AlertDialog.Builder(activity)
            .setTitle("Restore Complete")
            .setMessage("Your data has been restored.\n\n" +
                "If this backup was made on a different version of Termux " +
                "(different Android version or Termux app version), your package " +
                "versions may need updating.\n\n" +
                "Open Termux and run:\n" +
                "    apt update && apt upgrade\n\n" +
                "This will synchronize package versions with the repository.")
            .setPositiveButton("Got it", null)
            .setNegativeButton("Open Termux", (d, w) -> {
                // Start TermuxActivity
                Intent intent = new Intent(activity, TermuxActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(intent);
            })
            .show();
    } else {
        // Для backup — Toast как было
        Toast.makeText(activity, getString(successMsgRes),
            Toast.LENGTH_LONG).show();
    }
}
```

**Необходимые изменения:**
1. Передать флаг `isRestore` в `runWithProgress()` (или сделать два перегруженных метода)
2. Добавить string resources для текста диалога
3. Импорт `TermuxActivity`

### Вторично: Обновить pre-restore warning

В `strings.xml`:
```xml
<string name="backup_restore_warning_restore">
    Restoring will OVERWRITE the current Termux data directory…
    This cannot be undone. It is recommended to back up first.
    
    Note: If the backup file is from a different Termux version, 
    the installed packages may need updating after restore.
</string>
```

### Третично: Финализировать документацию

Обновить `docs/en/backup-restore-cross-version-analysis.md`:
- Убрать рекомендацию "автоматический apt update" (невозможно)
- Уточнить: post-restore диалог + документация
- Добавить ссылку из README / Settings

---

## 4. Итоговая таблица решений

| Вариант | Решение | Обоснование |
|---------|---------|-------------|
| (a) Toast | ❌ Заменить на AlertDialog | Toast нечитаем, не содержит actionable команд |
| (a') AlertDialog | ✅ **ПЕРВИЧНО** | Persistent, actionable, low-risk |
| (b) Документация | ✅ **ВТОРИЧНО** | Необходимо для permanent reference |
| (c) Auto-run apt | ❌ Невозможно | Нет shell during SAF; apt может не запуститься; restore ≠ package mgmt |

### Почему не Toast, а Dialog?

| Критерий | Toast | AlertDialog |
|----------|-------|-------------|
| Время показа | 3.5 сек | До dismiss |
| Команды читаемы | ❌ | ✅ |
| Action button (Open Termux) | ❌ | ✅ |
| Copy-paste команды | ❌ | ❌ (но читаемы) |
| Навязчивость | Низкая | Средняя (но одна кнопка) |
| Код изменения | Минимальное | Умеренное |

**Итог:** AlertDialog — наилучший баланс между видимостью рекомендации и ненавязчивостью.

---

## 5. Риск при невыполнении рекомендации

Если пользователь **НЕ** выполнит `apt update && apt upgrade` после cross-version restore:

| Последствие | Вероятность | Влияние |
|-------------|-------------|---------|
| `apt upgrade` падает с file conflict | Высокая | Среднее |
| Некоторые бинарники не стартуют (SO symlinks) | Высокая | Высокое |
| Shell не запускается (termux-exec LD_PRELOAD) | Средняя | Критическое |
| `apt update` выдаёт NO_PUBKEY | Средняя | Среднее |
| Несовместимый mirror | Низкая | Среднее |

**Вывод:** Cross-version restore **без** `apt update && apt upgrade` имеет **высокий риск** получить нерабочее окружение. Рекомендация обязательна.

---

*Анализ подготовлен для Termux Backup/Restore feature. После реализации диалога и документации — закрыть как готово.*
