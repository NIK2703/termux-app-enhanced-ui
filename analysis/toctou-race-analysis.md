# Сценарий 8: TOCTOU Race — Symlink Swap Between Wipe and Extract

## Дата: 2026-07-10
## Контекст: Анализ безопасности Termux restore

---

## Исходный код: restore sequence

Из `TermuxBackupUtils.restore()` (строки 145–222):

```
(1) pb.start()                                 // tar fork+exec, бинар в памяти
(2) FileUtils.deleteDirectoryFile(filesDir)     // recursive wipe files/
(3) fd.mkdirs()                                 // recreate empty files/
(4) errPump.start() + dataPump.start()          // tar начинает читать stdin и писать файлы
```

Окно TOCTOU: между (2) (wipe завершён) и (3)+(4) (mkdirs + tar начал писать).

---

## 1. Может ли другое приложение создать symlink в files/ после wipe?

**Короткий ответ: НЕТ для unprivileged приложений. Только Termux (u0_a213) или root.**

### DAC (Linux Discretionary Access Control)

| Path | Owner | Mode (типично) |
|---|---|---|
| `/data/data/` | `system` / `root` | `rwx--x--x` (0711) |
| `/data/data/com.termux/` | `u0_a213` | `rwx------` (0700) |
| `/data/data/com.termux/files/` | `u0_a213` | `rwx------` (0700) |

Каждое приложение на Android получает уникальный UID при установке. Для Termux (package `com.termux`) это `u0_a213` на однопользовательском устройстве (ID пользователя 0, app ID 213). Другие приложения имеют *другие* UID и не имеют доступа `rwx` к `/data/data/com.termux/`.

- `u0_a213` — **единственный** непривилегированный UID, который может писать в `/data/data/com.termux/files`.
- Другие приложения даже не могут **читать** содержимое этой директории.
- Linux `root` (UID 0) может, но это требует root-доступа (KernelSU / Magisk).

### SELinux

- Контекст: `u:object_r:app_data_file:s0:c213,c256,c512,c768`
- SELinux policy на Android запрещает cross-app доступ к `app_data_file` — только процесс в домене `untrusted_app` или `priv_app` данному UID может писать.
- Домен `untrusted_app_25` для Termux не пересекается с доменами других приложений.

### Вывод по (1)

**Атакующий не может создать symlink в `/data/data/com.termux/files/` если он не:**

| Агент | Может создать symlink? | Примечание |
|---|---|---|
| Другое unprivileged приложение | ❌ | DAC + SELinux блокируют |
| Termux (сам себя) | ✅ | Теоретически может, но это нарушит собственное восстановление |
| KernelSU root | ✅ | Контролируемый пользователем root |
| Malware UID u0_a213 | ✅ (теоретически) | Потребовалась бы компрометация самого Termux |

---

## 2. Реалистичность атаки

### Оценка: НИЗКАЯ на POCO F5 с KernelSU

**Почему низкая:**

1. **Нет adversarial unprivileged процесса.** Другие приложения не видят `/data/data/com.termux/`. Абсолютное DAC-разделение Android не позволяет сторонним процессам даже листинговать директорию.

2. **Malware с UID u0_a213 маловероятна.** Это потребовало бы либо:
   - Compromise самого Termux (RCE через пакетный менеджер или эксплойт в tar-архиве) — но тогда symlink-атака избыточна, так как malware уже имеет прямой доступ ко всем данным Termux.
   - Установку подписанного APK с таким же UID — Android Package Manager запрещает конфликт UID для разных package names.

3. **KernelSU root — это сам пользователь.** На POCO F5 с KernelSU пользователь явно управляет, какие приложения получают root (через whitelist / `allowlist`). Если пользователь дал root вредоносному приложению, атака возможна, но это failure на уровне пользователя, не Termux. Даже без restore, malware с root может делать всё что угодно.

4. **Временное окно крайне узкое.**
   - `deleteDirectoryFile` и `fd.mkdirs()` выполняются последовательно в одном потоке (UI-тред TermuxBackupUtils)
   - Между ними нет yield/await — окно составляет микросекунды
   - На практике attacker должен успеть вставить mkdir + symlink между возвратом из `deleteDirectoryFile` и вызовом `fd.mkdirs()`, или между `fd.mkdirs()` и тем как dataPump начнёт передавать данные tar

| Вектор | Реалистичность | Обоснование |
|---|---|---|
| Unprivileged app race | НЕВОЗМОЖНА | Нет доступа к files/ |
| Termux self-race | НИЗКАЯ | Malware в Termux уже имеет полный доступ |
| KernelSU root race | НИЗКАЯ | Root контролируется пользователем |
| Внешняя атака | НИЗКАЯ | Нет сетевого/IPC интерфейса к restore |
| Race выигрыш | НИЗКАЯ | Окно микросекунды; требуются параллельный поток + точный тайминг |

---

## 3. Защита: SecureDirectoryStream

### Что такое SecureDirectoryStream

`java.nio.file.SecureDirectoryStream<T>` — это NIO-провайдер, который позволяет выполнять файловые операции (creat, unlink, readlink) относительно открытого дескриптора директории, а не по строковому пути. Это исключает TOCTOU race: если вы открыли `dir_fd` (через `opendir()`), то `unlinkat(dir_fd, "foo", 0)` гарантированно удаляет `foo` внутри *той* директории, даже если `foo` был заменён на symlink между проверкой и операцией.

### Используется ли сейчас?

В коде есть **ссылка** на SecureDirectoryStream в комментарии (FileUtils.java lines 1320–1336), но:
- **Не используется.** `deleteRecursively` вызывается с `RecursiveDeleteOption.ALLOW_INSECURE` (line 1339), что явно разрешает обход без защиты от race.
- На Android API 26+ (8.0 Oreo) `SecureDirectoryStream` (через `UnixSecureDirectoryStream`) физически доступен в libcore, но Guava `MoreFiles.deleteRecursively()` с `ALLOW_INSECURE` не пытается его использовать.

### Нужна ли защита?

**НЕТ, для данного сценария — не нужна.** Аргументы:

1. **SecureDirectoryStream защищает от cross-process race, но здесь cross-process race невозможна** (см. раздел 1 — DAC/SELinux блокируют).

2. **SecureDirectoryStream защищает wipe, но не tar extraction.** Даже если заменить `deleteRecursively` на кастомную реализацию с `SecureDirectoryStream`, tar-процесс после этого всё равно пишет файлы через обычные POSIX-вызовы (`openat`, `symlinkat`, `mkdirat`). SecureDirectoryStream на стороне Java не может контролировать, как tar взаимодействует с файловой системой.

3. **Полная защита потребовала бы:**
   - Замены tar на кастомный extractor, который работает через `SecureDirectoryStream`, ИЛИ
   - Использования `openat()`-based extraction внутри контейнера (mount namespace), ИЛИ
   - Добавления флагов `--no-overwrite-dir` и `--delay-directory-restore` в tar

4. **Стоимость > выгода.** SecureDirectoryStream в Java потребует:
   - Написания кастомного `FileVisitor` с открытым `SecureDirectoryStream`
   - Отказ от Guava MoreFiles (который @Beta)
   - Обработки исключений для Android-специфичной реализации `UnixSecureDirectoryStream`
   
   Для сценария с оценкой LOW risk это неоправданные затраты.

---

## Рекомендация

**Не внедрять SecureDirectoryStream. Приоритет: LOW.**

```
┌──────────────────────────────────────────────────────────────┐
│ ОЦЕНКА: TOCTOU symlink race — LOW risk                       │
│                                                              │
│ Причина: DAC/SELinux полностью блокируют cross-app доступ     │
│ к /data/data/com.termux/files. Только Termux (при компромете │
│ которого symlink-атака избыточна) или root (контролируемый   │
│ пользователем) могут писать туда.                            │
│                                                              │
│ Рекомендация: Документировать как accepted risk.             │
│ SecureDirectoryStream не оправдан.                            │
└──────────────────────────────────────────────────────────────┘
```

### Дополнительные mitigation (low effort / high value)

1. **Флаг `--no-overwrite-dir` для tar** — предотвращает перезапись самой директории `files/`, если она вдруг окажется symlink-ом:
   ```
   tar -xzpf - --numeric-owner --no-same-owner --no-overwrite-dir -C filesDir
   ```
   Этот флаг поддерживается в GNU tar (который использует Termux).

2. **Проверка после mkdirs, что files/ не symlink** — дешёвая проверка через `Files.isSameFile()` или `lstat`:
   ```java
   Path filesPath = Paths.get(filesDir);
   if (Files.isSymbolicLink(filesPath) || !Files.isDirectory(filesPath, LinkOption.NOFOLLOW_LINKS)) {
       // abort — files/ is not a real directory
   }
   ```

3. **Логирование при обнаружении race** — если wipe не удалил все файлы (см. `ERRNO_FILE_STILL_EXISTS_AFTER_DELETING`), логировать как security event.

---

## Сводная таблица

| Аспект | Детали |
|---|---|
| **Уязвимость** | TOCTOU между wipe и extract |
| **Вектор** | Symlink в `/data/data/com.termux/files/` перенаправляет tar |
| **DAC** | `u0_a213:u0_a213 rwx------` — только Termux и root |
| **SELinux** | `u:object_r:app_data_file` — cross-app block |
| **Атакующий с unpriv** | ❌ Не имеет доступа |
| **Атакующий с Termux UID** | Теоретически возможен, но symlink-атака избыточна |
| **Атакующий с KernelSU root** | Возможен, но root контролируется пользователем |
| **Окно** | Микросекунды |
| **SecureDirectoryStream** | Не защитит от tar extraction (разные процессы) |
| **Рекомендация** | Принять риск. Добавить `--no-overwrite-dir` к tar |
| **Приоритет** | LOW — не блокирующий |
