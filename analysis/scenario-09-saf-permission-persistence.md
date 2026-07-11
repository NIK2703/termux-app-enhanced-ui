# Сценарий 9: SAF Permission Persistence — Анализ и Рекомендация

## Контекст

Репозиторий: `termux-app-ui-improve`
`targetSdkVersion` = 28 (Scoped Storage не форсируется)
`compileSdkVersion` = 34 (новые API доступны)

## 1. Текущая архитектура backup/restore

### 1.1. Fragment → SAF → Stream

**`TermuxPreferencesFragment.java`** (строки 150–185):

```
BACKUP:  Intent(ACTION_CREATE_DOCUMENT) → Uri → ContentResolver.openOutputStream(uri)
RESTORE: Intent(ACTION_OPEN_DOCUMENT)   → Uri → ContentResolver.openInputStream(uri)
```

- Stream открывается через `activity.getContentResolver()` внутри `onActivityResult()`
- Используется try-with-resources: `try (OutputStream out = ...)` / `try (InputStream in = ...)`
- **`takePersistableUriPermission()` НЕ вызывается** — нигде в проекте
- Работа запускается через `runWithProgress()` → `new Thread(() -> ...).start()` (простой поток)

### 1.2. Data pump

**`TermuxBackupUtils.java`:**

```
ProgressDialog (UI)
        │
        ▼
  new Thread ──► dataPump (Java thread)
                    │
                    ├── читает SAF InputStream → пишет в tar stdin   (restore)
                    └── читает tar stdout → пишет в SAF OutputStream  (backup)
```

- `pumpError` (AtomicReference<IOException>) ловит ошибки I/O
- При ошибке → `process.destroy()`, rollback на restore (wipes `files/`)
- На backup rollback'а нет — частичный архив может остаться в выбранном пользователем месте

## 2. SAF Permission Lifetime

### 2.1. Механизм (Android 4.4+)

| Флаг | Поведение |
|------|-----------|
| `ACTION_CREATE_DOCUMENT` | URI permission живёт до уничтожения task stack Activity |
| `ACTION_OPEN_DOCUMENT` | URI permission живёт до уничтожения task stack Activity |
| `FLAG_GRANT_READ_URI_PERMISSION` | Автоматически добавляется системой |
| `FLAG_GRANT_WRITE_URI_PERMISSION` | Автоматически добавляется системой |

**Без `takePersistableUriPermission()`**:
- Permission привязан к жизненному циклу **ContentResolver** → **Application context** → **Process**
- Документация: *"The permissions you grant with the intent flags last until the activity stack of the receiving activity is destroyed."*
- На практике: пока жив процесс, жив и ContentResolver, жив и SAF stream
- После убийства процесса LMK (или ребута) → permission теряется

### 2.2. Почему takePersistableUriPermission не нужна (в текущей архитектуре)

- Backup/restore — однократная операция: открыли stream, прочитали/записали, закрыли
- Нет сценария "открыли URI, сохранили на будущее, перезапустили приложение и продолжили"
- **Для односессионной операции `takePersistableUriPermission()` — избыточен и не нужен**

## 3. LMK Risk Analysis

### 3.1. Сценарий убийства процесса

```
Пользователь запускает backup/restore (1.4 ГБ)
        │
        ▼
ProgressDialog + dataPump Thread + tar process
        │
        │  (30+ секунд I/O)
        ▼
Системе нужна память ──► LMK выбирает main process Termux
        │
        ├── Process убит → ContentResolver уничтожен
        ├── SAF stream (InputStream/OutputStream) разорван
        ├── dataPump читает/пишет — IOException
        ├── pumpError фиксирует ошибку, process.destroy()
        └── Результат: операция прервана
```

### 3.2. Факторы риска

| Фактор | Влияние |
|--------|---------|
| **tar не даёт прогресс-колбэков** | Система видит только безмолвный фон-поток; Activity может быть засваплена |
| **ProgressDialog удерживает Activity visible** | Да, но Activity — такой же кандидат на убийство при serious memory pressure |
| **Нет WakeLock при backup/restore** | Экран может погаснуть → процесс переходит в фон → oom_score_adj повышается |
| **Нет foreground service** | Процесс не имеет foreground-приоритета |
| **Backup на main process** | SAF stream открыт в контексте Activity; любой LMK убивает всё |
| **Restore имеет rollback** | Частичные данные откатываются (wipe files/) — но операция всё равно провалена |
| **Backup НЕ имеет rollback** | Частичный tar.gz остаётся в выбранной пользователем директории — data corruption |

### 3.3. Вероятность на практике

- Для 1.4 ГБ на современном устройстве с 6–8 ГБ RAM: **низкая**, но ненулевая
- Для устройств с 3–4 ГБ RAM + много запущенных приложений: **средняя**
- На devices с aggressive memory management (MIUI/HyperOS, EMUI, OneUI): **выше среднего**
- Ключевой триггер: если пользователь сворачивает приложение во время backup (экран гаснет или он переключается в другое приложение)

## 4. Оценка foreground service

### 4.1. Что даёт foreground service при backup/restore

| Аспект | Без foreground service | С foreground service |
|--------|----------------------|---------------------|
| **oom_score_adj** | ~0–6 (Activity visible, но может быть засваплен) | -17 (foreground proc) |
| **LMK vulnerability** | Подвержен | Защищён (крайний кандидат) |
| **WakeLock** | Не используется | Можно добавить |
| **Notification** | Нет (кроме ProgressDialog) | Да (обязателен для FGS) |
| **Process survival** | Гарантий нет | Почти гарантировано |
| **Пользовательский опыт** | ProgressDialog блокирует UI | Notification + фон |

### 4.2. Когда foreground service оправдан

- Операция длится **> 5 минут**
- Потеря данных **критична** (rollback невозможен)
- Процесс должен пережить **сворачивание приложения**
- Операция не может быть прервана и возобновлена

### 4.3. Для backup/restore в termux-app

**Аргументы ПРОТИВ foreground service:**
1. **Избыточность для типичного сценария**: backup/restore — редкая операция (раз в месяц/квартал)
2. **Notification — UX-шум**: пользователь получит уведомление, которое не может убрать
3. **tar уже имеет защиту restore rollback**: при ошибке files/ откатывается
4. **SAF stream — основной канал**: даже с FGS, если SAF permission умрёт (ребут во время операции), FGS не спасёт
5. **Миграция кода**: нужно декларировать `FOREGROUND_SERVICE_TYPE_DATA_SYNC` (Android 14+), создавать новый Service или расширять существующий TermuxService
6. **targetSdk=28**: `FOREGROUND_SERVICE` permission уже есть в манифесте

**Аргументы ЗА (ограниченный):**
1. **Защита от LMK на devices с малым RAM**
2. **Возможность держать WakeLock** → процесс не уснёт при выключенном экране
3. **Пользователь явно инициирует операцию** → фоновое уведомление оправдано (как download manager)

## 5. Анализ альтернатив (до FGS)

### 5.1. Лёгкие улучшения без foreground service

| Подход | Сложность | Эффект |
|--------|-----------|--------|
| **WakeLock** в runWithProgress() | Низкая (3 строки через PowerManager) | Предотвращает sleep, но не убийство LMK |
| **Partial WakeLock** на время операции | Низкая | Процесс не уходит в глубокий сон |
| **Замена ProgressDialog на Notification** (без FGS) | Средняя | Уведомление не защищает от LMK, только UX |
| **cancel(false) на ProgressDialog** | Низкая | Не даёт системе убить Activity с диалогом |
| **Увеличение `oom_score_adj` через WorkManager** | Высокая | Не для long-running одной операции |

### 5.2. WakeLock — минимальное улучшение

```java
// В runWithProgress():
PowerManager pm = (PowerManager) activity.getSystemService(Context.POWER_SERVICE);
PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "termux:backup");
wl.acquire(10 * 60 * 1000L); // 10 минут максимум
try {
    Error error = worker.doWork();
    ...
} finally {
    if (wl.isHeld()) wl.release();
}
```

**Ограничение**: PARTIAL_WAKE_LOCK предотвращает suspend CPU, но не защищает процесс от LMK.

### 5.3. Foreground Service с низкоприоритетным Notification

Можно запустить foreground service с minimal notification (маленькая иконка в статус-баре, без звука/вибрации):

```java
Notification notif = new Notification.Builder(context, CHANNEL_ID)
    .setContentTitle("Termux Backup")
    .setContentText("Creating backup archive...")
    .setSmallIcon(android.R.drawable.stat_sys_upload)
    .setOngoing(true)
    .setPriority(Notification.PRIORITY_MIN)
    .build();
startForeground(NOTIFICATION_ID, notif);
```

## 6. Рекомендация

### Вердикт: Foreground service избыточен, но WakeLock + deferred start — целесообразны

**Почему FGS избыточен:**
1. backup/restore — редкая, короткая (1–3 мин для 1.4 ГБ на UFS) операция
2. `targetSdk=28` → Scoped Storage не форсируется, SAF не единственный путь
3. restore имеет rollback — данные не теряются безвозвратно
4. Для constant-termux-users процесс уже protected через TermuxService (foreground notification for terminal sessions)
5. Пользователь, запускающий backup, почти наверняка держит Termux на экране — Activity visible

### Что стоит сделать (ordered by priority):

#### Level 1 (немедленно, низкая стоимость):
1. **Добавить PARTIAL_WAKE_LOCK** в `runWithProgress()` на время операции — предотвращает прерывание от сна устройства
2. **Увеличить буфер dataPump** с 32KB до 256KB для SAF streams — меньше системных вызовов, быстрее завершение, меньше окно уязвимости

#### Level 2 (если происходят LMK-kills на практике):
3. **Перенести backup/restore на WorkManager** с `keepActive()` — современный API для long-running work, без notification
4. **Или использовать foreground service через существующий TermuxService**, запуская через `startCommand()` с action, который TermuxService уже обрабатывает

#### Level 3 (не рекомендуется сейчас):
5. **`takePersistableUriPermission()`** — не нужен для одноразовой операции, добавит complexity (нужен Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
6. **Foreground Service** — добавлять только если Level 1+2 не решают проблему на целевых устройствах

### Итоговая таблица рисков

| Угроза | Вероятность | Ущерб | Нужен FGS? |
|--------|------------|-------|-----------|
| LMK убивает main process | Низкая–средняя | Потеря операции (backup: partial archive; restore: rollback) | Нет |
| Device suspend (экран выкл) | Средняя | dataPump замедляется, но не умирает | Нет (WakeLock решает) |
| Ребут во время операции | Очень низкая | Полная потеря операции | Нет (FGS не спасёт) |
| ANR от длительного I/O на main thread | Нет (dataPump — отдельный поток) | — | — |

### Вывод

**Foreground service для однократного tar backup/restore — избыточен.** Реальная угроза не в LMK, а в device suspend (уход в сон при выключенном экране). Основная рекомендация — добавить PARTIAL_WAKE_LOCK и увеличить I/O буфер. FGS — последнее средство, если на конкретных устройствах (особенно MIUI/HyperOS с агрессивным фон-менеджментом) подтвердятся LMK-убийства активного backup'а.
