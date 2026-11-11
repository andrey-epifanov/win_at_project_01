# Инструкция по использованию — at_project_01_java

> UI-автотест для `ReportSearch.exe` (Delphi / Lazarus, Win32, LCL).
> Стек: **Java 17 + Maven + Spring Boot + JUnit Jupiter + JNA**.
> Конфиг — `src/test/resources/application.properties`. Скриншоты — `screenshots/`.

---

## 1. Назначение

Проект автоматизирует сценарий из задания (все 5 шагов) и сохраняет скриншот
каждого шага в `screenshots/`:

1. Открыть папку с отчётами.
2. Найти отчёт `otchet_kassa_may_2026`.
3. Открыть его.
4. Нажать «Закрыть» → закрывается окно №2 (окно просмотра).
5. Нажать крестик (X) первого окна → оно закрывается, приложение завершается.

Тест запускает реальное приложение (`ProcessBuilder`), управляет им через
нативные вызовы Win32 (JNA), проверяет состояние и завершает процесс.

---

## 2. Быстрый старт

Откройте PowerShell (или консоль) из папки `D:\workspace_delphi\at_project_01_java`.

```powershell
# 1) Переменная для JNA (снимает предупреждения про restricted methods в JDK 25)
$env:MAVEN_OPTS = "--add-opens=java.base/java.lang=ALL-UNNAMED"

# 2) Запуск теста
mvn test
```

Если Maven не в PATH, вызывать явно:

```powershell
& "C:\Users\54245\apache-maven-3.9.7\bin\mvn.cmd" -f pom.xml test
```

Если до этого тест «завис» или остались зомби-процессы — убрать перед повтором:

```powershell
Get-Process java,ReportSearch -ErrorAction SilentlyContinue | Stop-Process -Force
```

### Ожидаемый результат

```
[DIAG] folder native text after typing = D:\workspace_delphi\project_01\reports
[DIAG] attempt 1 count=1 status-bar=[...]
[SHOT] ...\screenshots\step_01_reports_folder.png
[SHOT] ...\screenshots\step_02_found_kassa.png
[SHOT] ...\screenshots\step_03_opened_xml.png
[SHOT] ...\screenshots\step_04_viewer_closed.png
[RESULT] ALL STEPS PASSED
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

---

## 3. Особенности, которые надо знать заранее

### 3.1 Приложение ищет по СОДЕРЖИМОМУ, а не по имени файла

В XML-отчёте **нет** строки `otchet_kassa_may_2026`. Поэтому в поле поиска
вводится уникальная **строка из содержимого** нужного отчёта:
`Отчет по кассе за май 2026` (`app.search.needle`).
Приложение открывает каждый XML, ищет эту строку внутри и возвращает файл
`otchet_kassa_may_2026.xml`. Именно поэтому в поле поиска вводится текст, а не имя файла.

### 3.2 Кириллица в конфиге хранится как `\uXXXX`

`application.properties` — это обычные ASCII-значения (без кириллицы), а строки
с кириллицей закодированы как unicode-escapes. **Spring их НЕ декодирует** —
декодирование делает `ReportSearchDriver` в конструкторе через
`UnicodeEscapes.decode(...)` (титулы окон, кнопки, игла поиска).

### 3.3 .java-файлы — ASCII-чистые

Из-за unicode-escape-препроцессора `javac` **нельзя** класть `\uXXXX` или
кириллицу в комментарии `.java`. Кириллица разрешена только внутри строковых
литералов как UTF-8. Любая правка теста/драйвера должна это учитывать.

### 3.4 Запуск приложения

Приложение стартует через `ProcessBuilder` с `redirectErrorStream(true)`;
PID фиксируется (`process.pid()`). Все окна ищутся **по этому PID**, а не по
«любому окну с таким заголовком» — это исключает захват чужих окон от других
экземпляров программы.

---

## 4. ГЛАВНОЕ: как происходит адресация к Win32-формам и контролам

Адресация построена на чистом Win32/JNA (без pywinauto). Иерархия такая:

```
HWND топ-уровня (top-level root window)
   └── HWND дочерних контролов (Edit / Button / LCLListBox / msctls_statusbar32)
        └── ещё более вложенные контролы (через EnumChildWindows)
```

Всё адресование идёт «от окна → к потомкам». Каждый шаг:

### 4.1 Шаг 1 — найти корневые окна процесса

`Win32.INSTANCE.EnumWindows(...)` перебирает **все top-level окна** системы.
Для каждого `GetWindowThreadProcessId(hWnd, &pid)` проверяет владельца.
`WindowFinder.findTitle(pid, title)` возвращает первое **видимое** top-level
окно, у которого `pid == нашPID` **И** `GetWindowTextW(...) == title`
(точное совпадение).

### 4.2 Шаг 2 — фильтр по заголовку (точному или префиксу)

- Главное окно: `заголовок == "Поиск по отчётным формам"` (`app.main.title`).
- Окно просмотра: `заголовок начинается с "Просмотр"` (`app.viewer.title.prefix`),
  потому что к названию файла добавляется префикс. Для этого есть
  `WindowFinder.findTitleStartsWith(pid, prefix)`.

Текст окна читается `GetWindowTextW`/`GetWindowTextLengthW` (без переполнения
буфера). Для управления адресацией `NativeWindow` хранит сам **HWND** и через
него делает все операции.

### 4.3 Шаг 3 — «внутрь» окна: перечисление дочерних контролов

От HWND окна `EnumChildWindows(окно, callback)` перебирает всех потомков
(включая вложенных). `GetClassNameW` читает класс окна. Дальше фильтруют по
классу:

| Класс окна (ClassNameW) | Что это |
|---|---|
| `Edit` | текстовое поле (ввод папки, ввод иглы поиска) |
| `Button` | кнопка (Поиск / Открыть XML / Закрыть) |
| `LCLListBox` | список результатов поиска |
| `msctls_statusbar32` | строка состояния |

### 4.4 Шаг 4 — адресация Edit-контролов

В главной форме **два** Edit. Их различают **не индексом**, а содержимым:

- `folderEdit(main)` (кадр `folderEdit(...)` в тесте): первый `Edit`, чей
  `GetWindowText` похож на путь диска `D:...` (`t.length() > 2 && t.charAt(1) == ':'`).
  Он изначально предзаполнен рабочим каталогом `D:\workspace_delphi\project_01`.
- `searchEdit(main)`: `Edit` с **пустым** текстом (`isEmpty()`). Это поле
  поисковой иглы.

Это устойчивее, чем `found_index=0/1`: не зависит от порядка дочерних окон.

### 4.5 Шаг 5 — адресация кнопок по тексту

Кнопки находятся по **точному тексту** окна:
`main.childByText(driver.buttonSearch(), "Button")` → кнопка «Поиск»,
`main.childByText(driver.buttonOpen(), "Button")` → «Открыть XML»,
`viewer.childByText(driver.closeBtnText(), "Button")` → «Закрыть».

### 4.6 Шаг 6 — выбор элемента списка

`LCLListBox`: `listBoxCount()` (число результатов), `listBoxItem(0)` (текст
первой строки → путь к файлу), `selectListBoxItem(0)` (выделить строку).

### 4.7 Шаг 7 — управление (сообщения Win32)

После того как `NativeWindow` получен, все действия — это Windows-сообщения:

| Действие | API / сообщение |
|---|---|
| Прочитать текст окна | `GetWindowTextW` / `GetWindowTextLengthW` |
| Прочитать текст Edit | `SendMessage(WM_GETTEXT)` / `WM_GETTEXTLENGTH` |
| Установить текст | `SetWindowTextW` |
| Ввести текст «как с клавиатуры» | `SendMessage(WM_SETTEXT)` (очистка) + `SendMessage(WM_CHAR)` посимвольно |
| Кликнуть кнопку | `SendMessageTimeout(BM_CLICK, ...)` |
| Клик через команду | `SendMessage(WM_COMMAND, BN_CLICKED, ...)` |
| Число строк списка | `SendMessage(LB_GETCOUNT)` |
| Текст строки списка | `SendMessage(LB_GETTEXTLEN)` / `SendMessage(LB_GETTEXT)` |
| Выбрать строку списка | `SendMessage(LB_SETCURSEL)` |
| Текст строки состояния | `SendMessage(SB_GETTEXTLENGTH)` / `SendMessage(SB_GETTEXT)` |
| Закрыть окно | `PostMessage(WM_CLOSE)` |

#### Почему именно так, а не иначе (важно для понимания):

- **Ввод текста** — только через `WM_CHAR`. `keybd_event` не доходит до окна из
  фонового forked-JVM (нет foreground), а `SetWindowTextW`/`EM_REPLACESEL`
  обновляют буфер, но **не** триггерят LCL-события `OnChange`, поэтому
  приложение не «видит» новый текст.
- **Чтение Edit** — через `SendMessage(WM_GETTEXT)`, а **не** `GetWindowText`,
  потому что `GetWindowText` для контрола в чужом процессе может вернуть
  «кэшированное» (устаревшее) значение LCL-текста. `WM_GETTEXT` читает
  фактический буфер контрола.
- **Клик** — `SendMessageTimeoutW(BM_CLICK)` вместо блокирующего
  `SendMessage(BM_CLICK)`, потому что LCL-поиск выполняется синхронно в потоке
  окна, и обычный `SendMessage` зависал бы, пока приложение занято. С таймаутом
  вызов возвращается в отведённое время даже при занятом потоке.
- **не зависает на чтениях**: все чтения списка/статусбара тоже идут через
  `SendMessageTimeoutW` с таймаутом; при таймауте `listBoxCount()` вернёт `-1`,
  что интерпретируется как «новый результат ещё не готов», и тест делает retry.

### 4.8 Фокус и foreground

Для ввода текста контрол должен получить фокус. Из фонового JVM
`SetForegroundWindow` **сам по себе запрещён** Windows. Как в pywinauto
`set_focus()`, метод `clickFocus()` делает:

1. `GetWindowThreadProcessId` — ID потока целевого окна.
2. `AttachThreadInput(свойТред, тредОкна, true)` — временно привязать очереди ввода.
3. `ShowWindow(SW_RESTORE)`, `BringWindowToTop`, `SetForegroundWindow`,
   `SetFocus(контрол)`.
4. `finally { AttachThreadInput(..., false) }` — отвязать.

### 4.9 Скриншоты

`java.awt.Robot` (захват экрана) **не работает** в surefire-сессии (нет
интерактивного десктопа). Поэтому `Screenshot.captureWindow` использует
`PrintWindow` + GDI `GetDIBits` и сохраняет PNG. Это не зависит от видимости
окна на экране.

---

## 5. Структура проекта

```
at_project_01_java
├─ pom.xml                                  # сборка, зависимости (Spring Boot, JUnit, JNA)
├─ screenshots/                             # сюда пишутся step_*.png
└─ src
   ├─ main/java/com/example/reporttest
   │  ├─ ReportTestApplication.java         # @SpringBootApplication
   │  └─ driver
   │     ├─ Win32.java                      # JNA-маппинг user32 (реальные экспорты)
   │     ├─ Gdi.java                        # JNA-маппинг gdi32 (PrintWindow/screenshot)
   │     ├─ NativeWindow.java               # обёртка HWND: text/class/children/click/typeText/...
   │     ├─ WindowFinder.java               # поиск top-level окон по PID+заголовку
   │     ├─ ReportSearchDriver.java         # Spring-компонент: запуск, PID, конфиг, тайминги
   │     ├─ Screenshot.java                 # PrintWindow + GetDIBits -> PNG
   │     └─ UnicodeEscapes.java             # декодер \uXXXX для properties
   └─ test
      ├─ java/com/example/reporttest
      │  └─ ReportSearchScenarioTest.java   # JUnit-тест (5 шагов) + адресация контролов
      └─ resources
         └─ application.properties          # конфиг: пути, титлы, игла, кнопки, тайминги
```

### Ключевые классы адресации

- **`Win32.java`** — тонкий JNA-интерфейс. ⚠️ JNA сопоставляет имя метода
  **напрямую** с именем нативного символа, поэтому **методы обязаны называться
  как реальные экспорты user32** (`GetWindowTextW`, `SendMessageW`, ...).
  Переименованные методы вызывают `UnsatisfiedLinkError`.
- **`NativeWindow.java`** — неизменяемая обёртка над `HWND`. Все операции идут
  через `handle()`. Это «единица адресации» — любая найденная форма/контрол
  представлена этим объектом.
- **`WindowFinder.java`** — поиск top-level окон по `pid` + заголовку.
- **`ReportSearchDriver.java`** — запуск процесса, PID, конфигурация, тайминги.

### Карта контролов главного окна (для адресации)

```
Главное окно "Поиск по отчётным формам"  (Edit-поля не индексируются — ищу по содержимому)
 ├─ Edit[папка]    — Edit с текстом вида "D:..."  -> app.reports.dir
 ├─ Edit[поиск]    — пустой Edit                  -> app.search.needle
 ├─ Button "Поиск"       -> app.button.search
 ├─ Button "Открыть XML" -> app.button.open
 ├─ LCLListBox            -> список результатов
 └─ msctls_statusbar32    -> строка состояния
Окно просмотра (префикс "Просмотр")
 └─ Button "Закрыть"      -> app.button.close
```

---

## 6. Как это запускается из кода последовательно (поток теста)

1. `driver.launch()` — старт exe, получение PID.
2. `driver.waitForMain(FIND_TIMEOUT_MS)` — `WindowFinder.waitForTitle(pid, mainTitle)`.
3. `folderEdit(main)` + `searchEdit(main)` — `EnumChildWindows(Edit)` → выбор по содержимому.
4. `folderEdit.typeText(app.reports.dir)` — `WM_SETTEXT("")` + `WM_CHAR` посимвольно.
5. `searchEdit.typeText(app.search.needle)`.
6. `searchBtn.click()` — `SendMessageTimeout(BM_CLICK)`; `listBox.listBoxCount()`
   (retry до 3 раз, если `count < 1`).
7. `assertEquals(1, count)`; `listBox.listBoxItem(0)` заканчивается на `otchet_kassa_may_2026.xml`.
8. `listBox.selectListBoxItem(0)`; `openBtn.click()`; `waitForViewer()` (префикс «Просмотр»).
9. `viewer.childByText("Закрыть","Button").click()`; проверить `isViewerVisible()==false`;
   `isMainVisible()==true`.
10. `main.postClose()` (`WM_CLOSE`); `isMainVisible()==false`; `driver.awaitExit(EXIT_TIMEOUT_MS)`.

---

## 7. Конфигурирование

Все «магические» значения — в `src/test/resources/application.properties`:

```properties
app.exe.path=D:\\workspace_delphi\\project_01\\ReportSearch.exe   # путь к exe
app.exe.workdir=D:\\workspace_delphi\\project_01                  # рабочий каталог
app.reports.dir=D:\\workspace_delphi\\project_01\\reports         # папка отчётов
app.main.title=\u041f\u043e\u0438\u0441\u043a ...                 # заголовок главного окна
app.search.needle=\u041e\u0442\u0447\u0435\u0442 ...               # игла поиска (содержимое)
app.report.expected.file=otchet_kassa_may_2026.xml
app.viewer.title.prefix=\u041f\u0440\u043e\u0441\u043c\u043e...    # префикс окна просмотра
app.button.search / .open / .close=...                             # тексты кнопок
app.wait.step.ms=400
app.wait.search.ms=1500
app.wait.after.click.ms=1000
app.launch.wait.ms=2000
app.screenshot.dir=D:\\workspace_delphi\\at_project_01_java\\screenshots
```

Менять можно пути/тайминги/заголовки. **Не** кладите кириллицу напрямую в
`.properties` — только как `\uXXXX`.

---

## 8. Возможные проблемы и решения

| Симптом | Причина | Решение |
|---|---|---|
| `UnsatisfiedLinkError: Error looking up function '...'` | Имя метода Java ≠ реальный экспорт user32 (напр. `GetCurrentThreadId` — он из **kernel32**, не user32) | Использовать `Kernel32.INSTANCE.GetCurrentThreadId()` или переименовать метод в точный экспорт |
| Текст в Edit не обновился | `SetWindowTextW` / `EM_REPLACESEL` не триггерят LCL `OnChange` | Ввод только через `WM_CHAR` (`typeText`) |
| Тест «висит» на клике | Блокирующий `SendMessage(BM_CLICK)` при занятом потоке окна | Использовать `SendMessageTimeoutW` (с таймаутом) |
| Список/статусбар «не читаются» | Обычный `SendMessage` блокируется при занятом потоке | Читать через `SendMessageTimeoutW` |
| `Could not capture screen (headless?)` | `java.awt.Robot` недоступен в forked-JVM / surefire | Скриншоты через `PrintWindow` + `GetDIBits` |
| Скриншот пустой | Неверный контекст / тайминг | Убедиться, что окно ещё существует; печатать `[SHOT] ...`; при необходимости увеличить `app.wait.*.ms` |

---

## 9. Чем этот проект отличается от Python-аналога

В `D:\workspace_delphi\at_project_01` лежит pywinauto-версия того же сценария.
Здесь — Java/JNA. Ключевые различия в адресации:

| Аспект | Java (JNA) | Python (pywinauto) |
|---|---|---|
| Библиотека | JNA, напрямую user32 | pywinauto (backend='win32') |
| Поиск top-level окон | `EnumWindows` + `GetWindowThreadProcessId` | `Application.connect(title=...)` / `Desktop.windows()` |
| Адресация Edit | по содержимому (`charAt(1)==':'` / пустой) | по индексу `found_index=1` / `found_index=0` |
| Ввод текста | `SendMessage(WM_CHAR)` | `set_text()` / `type_keys()` |
| Чтение Edit | `SendMessage(WM_GETTEXT)` | `window_text()` |
| Клик | `SendMessageTimeout(BM_CLICK)` | `.click()` |
| Скриншот | `PrintWindow` + `GetDIBits` | `capture_as_image()` |
| Закрытие | `PostMessage(WM_CLOSE)` | `post_message(0x0010)` |