# Сценарий 4: Полное восстановление из ручного root-бэкапа

**Команда восстановления:** `tar -xzpf backup.tar.gz --numeric-owner -C /data/data com.termux`

---

## 1. Нужно ли останавливать Termux процессы перед restore?

**Да, обязательно.** Без `pkill`/`am force-stop` есть следующие риски:

| Риск | Описание |
|------|----------|
| **Открытые файловые дескрипторы** | Termux-сессии, sshd, crond держат открытыми fd на аву, логи, БД. При перезаписи файла через `tar -x` процесс может писать в stale inode или получить SIGBUS при mmap. |
| **dpkg/apt race** | Если во время восстановления `$PREFIX/var/lib/dpkg/` перезаписывается, а concurrent dpkg процесс (авто-апдейт) пишет туда же — получаем битую database. |
| **sqlite corruption** | Termux'ы и пакеты активно используют sqlite (история команд, .mbshrc state, etc.). Перезапись файла во время открытой транзакции = corruption. |
| **pipe/socket остатки** | После восстановления старые сокеты (am.sock) уже мертвы, но файлы на диске могут сбить с толку новый процесс, пока их не пересоздал runtime. |

### Механизм остановки

```bash
# Принудительная остановка Termux (все процессы внутри контейнера):
am force-stop com.termux
# или
pkill -9 -f "com.termux"          # убивает только процессы в namespace termux
pkill -9 -f "/data/data/com.termux/files/usr/bin"   # перестраховка
```

> **Важно:** `pkill` убивает процессы, но Android Init/ActivityManager могут перезапустить Termux при получении broadcast (SMS, boot-completed и т.д.) или если есть foreground notification + service. См. п. 5(b).

---

## 2. `--no-same-owner` НЕ НУЖЕН при root

**Верно.** При запуске `tar` от root:
- флаг `--numeric-owner` восстанавливает uid/gid как числа из архива (u0_aXXX → u0_aXXX)
- root может выполнять `chown()` без ограничений
- `--no-same-owner` (по умолчанию для не-root) заставляет tar **сбрасывать** владельца на текущего пользователя — это **противоположно** тому, что нужно

**Правильный флаг:**
```bash
tar -xzpf backup.tar.gz --numeric-owner -C /data/data com.termux
```
- `-p` — сохраняет permissions
- `--numeric-owner` — uid/gid как числа (без name resolution)
- `--no-same-owner` — **НЕ НУЖЕН** и даже **вреден** (сбросит владельца на root, и потом Termux не сможет нормально работать)

---

## 3. Сокеты (am.sock, ssh-agent.socket) не архивировались — пересоздадутся

**Верно.** Unix domain sockets имеют тип `s` (socket) в ls -la и **не могут быть архивированы** tar — они просто пропускаются с warning:
```
tar: Removing leading '/' from member names
tar: socket is not supported: com.termux/files/usr/tmp/ssh-agent.socket
```

После restore:
- **am.sock** — создаётся заново Termux:Tasker/Accessibility bridge при первом вызове API
- **ssh-agent.socket** — пересоздаётся при запуске ssh-agent (как systemd user service или ручной старт)
- **Termux:API сокеты** — создаются по запросу клиентских скриптов (termux-api-start)

**Никаких дополнительных действий не требуется.**

---

## 4. Restore поверх существующих данных: tar не делает wipe

Это **ключевое отличие** от UI-восстановления.

### Как работает UI restore (TermuxBackupUtils.java):
```java
// 1. Запускаем tar (бинарник загружен в память)
Process tar = pb.start();

// 2. WIPE: удаляем всю files/ рекурсивно
FileUtils.deleteDirectoryFile(filesDir, ...);

// 3. Создаём пустую files/
new File(filesDir).mkdirs();

// 4. Только теперь кормим архив в tar
pumpArchive(tar.getOutputStream());
```

### Как работает ручной restore (`tar -x`):
```
tar -x -C /data/data com.termux.zip
  ↓
tar открывает archive.tar.gz
tar итерирует members из архива
для каждого:
  если файл существует → перезаписать (новый inode, те же имя)
  если не существует  → создать
  если в dest есть файлы НЕ из архива → они остаются
```

### Риски overwrite-only подхода:

**(a) Stale файлы**

| Тип | Пример | Последствие |
|-----|--------|-------------|
| Удалённые пакеты | `$PREFIX/lib/python3.11/site-packages/` (старый site-package, удалённый после бэкапа) | Висит мусор, может конфликтовать с новыми версиями |
| Старые конфиги | `$HOME/.config/` (если в архиве нет новой структуры) | Приложение читает оба — получает неконсистентное состояние |
| Временные файлы | `$TMPDIR/*` (в бэкап не попали, на диске остались) | Занимают место, не влияют на работу |

**(b) Symlink race**

Самый опасный сценарий (описан в TermuxBackupUtils.java):

```
Восстанавливаемый сруктура:           Stale symlink на диске:
files/usr/bin/tar              →      files/usr/bin → /sdcard/  (stale от старого restore)
                                       ↓
tar пытается развернуть tar в:         /sdcard/tar  → WTF
```

**Контриеры:**
- `tar -x` **НЕ следует symlink при записи** (GNU tar выводит `tar: Cannot extract — file is a symlink` и скипает). Однако symlink **на пути к целевой директории** — проблема.
- Флаг `--no-overwrite-dir` не даёт перезаписать права на существующие директории, но symlink-race на уровне parent dir — редкость при restore в `/data/data/com.termux`.

**(c) SELinux context**

При ручном restore через tar SELinux context файлов восстанавливается как `u:object_r:app_data_file:s0:cNNN` только если в tar сохранён xattrs (флаг `--xattrs`). **По умолчанию tar НЕ сохраняет xattrs.** Если бэкап делался без `--xattrs`, все SELinux context'ы будут неправильными, и Termux может не запуститься или работать с ошибками.

**В UI restore контексты проставляются автоматически** через Android runtime при создании файлов через ContentResolver / FileProvider.

---

## 5. Оценка риска

### (a) Нужен pkill Termux — ОБЯЗАТЕЛЬНО
Без pkill: гарантированный race + потенциальная коррупция dpkg/sqlite.

### (b) Race: между pkill и restore может перезапуститься Termux
**Реальная проблема на Android 12+:**

1. `am force-stop com.termux` → ActivityManager убивает процесс
2. Другие аппы отправляют broadcast (SMS, network change)
3. Termux имеет registered receiver → AM создаёт новый процесс
4. **Новый процесс стартует ПАРАЛЛЕЛЬНО** с tar restore
5. Новый процесс пишет в файлы, пока tar их перезаписывает → бинарная коррупция

**Вероятность:** низкая (если Termux не настроен на автозапуск), но ненулевая.

## 6. Рекомендуемая последовательность действий

```bash
#!/system/bin/sh
# ============================================================
# ПОЛНОЕ ВОССТАНОВЛЕНИЕ TERMUX ИЗ РУЧНОГО root-БЭКАПА
# ============================================================

BACKUP_FILE="/sdcard/termux-backup.tar.gz"   # ← путь к вашему бэкапу
TERMUX_DIR="/data/data/com.termux"

# ─── ШАГ 1: Принудительная остановка Termux ───────────────────
echo "[1/5] Останавливаю Termux..."
am force-stop com.termux
sleep 2

# ─── ШАГ 1.5: Блокировка автозапуска (Android 12+) ───────────
# Заморозка через pm, чтобы ОС не перезапустила процесс
# (опционально, но сильно снижает риск race)
pm disable com.termux/.app.TermuxService 2>/dev/null || true

# ─── ШАГ 2: Health-check бэкапа ──────────────────────────────
echo "[2/5] Проверяю архив..."
if ! gzip -t "$BACKUP_FILE"; then
    echo "ОШИБКА: архив повреждён!"
    exit 1
fi
echo "OK: архив цел."

# ─── ШАГ 3: WIPE (ОПЦИОНАЛЬНО, НО РЕКОМЕНДУЕТСЯ) ──────────────
# Безопаснее чем restore поверх: удаляем старые данные,
# чтобы не осталось stale файлов и symlink race.
echo "[3/5] Удаляю старые данные (opционально)..."
# НЕ удаляем сам /data/data/com.termux — только files/
rm -rf "$TERMUX_DIR/files" 2>/dev/null
mkdir -p "$TERMUX_DIR/files"
echo "OK: data directory очищен."

# ─── ШАГ 4: RESTORE ───────────────────────────────────────────
echo "[4/5] Восстанавливаю данные..."
tar -xzpf "$BACKUP_FILE" \
    --numeric-owner \
    --no-overwrite-dir \
    -C /data/data \
    com.termux

TAR_EXIT=$?
if [ $TAR_EXIT -ne 0 ]; then
    echo "ОШИБКА: tar завершился с кодом $TAR_EXIT"
    exit 1
fi
echo "OK: данные восстановлены."

# ─── ШАГ 5: Восстановление SELinux context (если бэкап без xattr) ──
echo "[5/5] Восстанавливаю SELinux context..."
restorecon -R -F "$TERMUX_DIR/files" 2>/dev/null || \
    echo "WARN: restorecon недоступен — context может быть неправильным."

# Разархивируем Termux для автозапуска
pm enable com.termux/.app.TermuxService 2>/dev/null || true

echo ""
echo "✓ Восстановление завершено."
echo "  Откройте Termux и запустите 'termux-info' для проверки."
```

### Ключевые моменты скрипта:

| Действие | Зачем |
|----------|-------|
| `am force-stop` + `sleep 2` | Дать процессам умереть, освободить fd |
| `pm disable TermuxService` | Заблокировать автозапуск между wipe и restore (митигация race) |
| `gzip -t` | Проверить целостность архива до начала restore |
| `rm -rf files/` + `mkdir` | **Опциональный wipe** — убирает stale файлы и symlink race. Пропускать только если очень нужно сохранить файлы вне архива. |
| `--numeric-owner` | Восстановить uid/g1d (u0_aXXX → u0_aXXX) |
| `--no-overwrite-dir` | Защита от случайного изменения прав на существующие директории |
| `restorecon -R -F` | Принудительно восстановить SELinux context (если бэкап без `--xattrs`) |
| `pm enable` | Вернуть автозапуск |

---

## Сравнение: UI restore vs ручной root restore

| Аспект | UI Restore (через Android) | Ручной root restore (tar -x) |
|--------|---------------------------|------------------------------|
| **Wipe перед restore** | ✅ Да (полный wipe files/) | ❌ Нет (overwrite-only; нужен ручной rm) |
| **Tar binary loaded in memory** | ✅ Да (pre-fork) | ❌ Нет (бинарник перезаписывается во время работы) |
| **SELinux context** | ✅ Автоматически от runtime | ❌ Только если бэкап с `--xattrs` (нужен restorecon) |
| **Stale files cleanup** | ✅ Полный wipe | ❌ Остаётся мусор |
| **Symlink race** | ✅ Невозможен (wipe перед tar) | ❌ Возможен (если не делать wipe) |
| **Process isolation** | ✅ Нет проблем (Service остановлен AM) | ⚠️ Нужен ручной pkill + guard |
| **Простота** | Tap-и-выбери-файл | root shell + скрипт |

**Вывод:** UI restore через TermuxBackupUtils.java **безопаснее** ручного root restore благодаря wipe + pre-fork tar. Ручной restore возможен, но **обязательно** с pre-restore wipe и pkill.
