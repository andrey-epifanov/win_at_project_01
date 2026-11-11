# win.driver — Playwright-like Win32 automation toolkit

`win.driver` is a small, self-contained Java toolkit for driving native Win32
applications (Windows Forms / Delphi / Lazarus / classic Win32 windows) in the
same spirit as Playwright: you launch an app, find a window, address its child
controls with descriptive selectors, act on them, and capture screenshots.

It is generic — it does NOT know about ReportSearch.exe.  The same API can be
used to automate any Win32 window.

---

## Dependencies

- JNA + jna-platform (already in `pom.xml`)
- Java 17+

## Examples

### Launch an app and find a window

```java
WinProcess app = WinDriver.launch(exePath, workDir);
WinWindow main = app.mainWindow(By.title("My App"), 10000);   // wait up to 10s
```

### Address child controls

```java
// by Win32 class
WinWindow edit = main.child(By.className("Edit"));

// by exact text / caption
WinWindow ok = main.child(By.text("OK"));

// pick the n-th match
WinWindow folder = main.child(By.className("Edit").nth(1));
```

### Lazy locator (resolves each time you act)

```java
main.locator(By.className("Edit").nth(1)).fill("hello");
main.locator(By.text("OK")).click();
int count = main.locator(By.className("Button")).count();
```

### Type into an Edit control

```java
main.locator(By.className("Edit")).fill("C:\\data\\reports");
System.out.println(main.locator(By.className("Edit")).value());
```

### Screenshot

```java
main.screenshot(Path.of("shots"), "main.png");
```

### Cleanup

```java
app.terminate();   // or try-with-resources (WinProcess implements AutoCloseable)
```

---

## API overview

| Class | Role | Playwright analogue |
|---|---|---|
| `WinDriver` | entry point: `launch(exe, workdir)` | `playwright()` |
| `WinProcess` | a launched app process; finds top-level windows by PID | Browser/context |
| `WinWindow` | a native HWND (top-level window or child control) | Page / element |
| `By` | declarative selector (class / text / textContains / nth) | locator selector |
| `Locator` | lazy handle tied to a scope window | Locator |
| `WinKey` | synthetic keyboard (modifier chords) | keyboard |
| `WinException` | thrown on not found / timeout | Playwright errors |

## Selectors (By)

- `By.className("Edit")` — Win32 window class (`GetClassNameW`)
- `By.text("OK")` / `By.title("...")` — exact control text / window title
- `By.textContains("xml")` — substring match
- `.nth(n)` — pick the n-th matching sibling (default 0)

Simple string forms for `locator(String)`:

- `"#Edit"` → class name
- `"text=OK"` → exact text
- `"title=..."` → exact title

## WinWindow actions

| Method | What it does |
|---|---|
| `text()` / `title()` | window/control text (`GetWindowText`) |
| `className()` | Win32 window class |
| `editText()` | Edit buffer (`SendMessage WM_GETTEXT`) |
| `fill(value)` | clear + type into Edit via `WM_CHAR` |
| `click()` | `SendMessageTimeoutW(BM_CLICK)` |
| `clickCommand()` | `WM_COMMAND + BN_CLICKED` |
| `clickFocus()` | focus control + foreground window (AttachThreadInput) |
| `close()` | `PostMessage(WM_CLOSE)` |
| `children()` / `children(By)` | enumerate descendants |
| `child(By)` | first matching descendant (throws if none) |
| `locator(By)` | lazy Locator |
| `screenshot(dir, name)` | PrintWindow + GDI → PNG |
| `rect()/width()/height()` | geometry |

## Keyboard / control keys (WinKey)

Send control keys (Enter, Delete, Tab, Escape, Backspace, arrows, F1..F12) to a
focused control.  `keybd_event` targets the FOREGROUND window, so call
`goToThisWindow()` (or `clickFocus()`) first to bring the window to front.

```java
main.goToThisWindow();
folder.enter();            // VK_RETURN
folder.del();              // VK_DELETE
folder.tab();              // VK_TAB
folder.escape();           // VK_ESCAPE
folder.arrow(WinKey.VK_DOWN);
WinKey.chord(WinKey.VK_CONTROL, WinKey.VK_RETURN);  // Ctrl+Enter
```

Relevant VK constants are in `WinKey`: `VK_BACK, VK_TAB, VK_RETURN, VK_ESCAPE,
VK_SPACE, VK_END, VK_HOME, VK_LEFT, VK_UP, VK_RIGHT, VK_DOWN, VK_DELETE, VK_SHIFT,
VK_CONTROL, VK_MENU`.

## Checkboxes

A Win32 checkbox is a `Button` control with the `BS_CHECKBOX` (or `BS_AUTOCHECKBOX`)
style.  Read its state and toggle it:

```java
WinWindow cb = main.child(By.text("Enable logging"));
cb.isCheckBox();          // true if it is a checkbox
cb.isChecked();           // true if checked
cb.checkState();          // BST_CHECKED / BST_INDETERMINATE / BST_UNCHECKED
cb.setChecked(true);      // click if not already checked (fires OnClick)
cb.setCheck(true);        // set state without firing OnClick
cb.click();               // plain click
```

## Checkboxes inside a table

A checkbox sitting in a grid/table cell is still a Win32 control, so **you treat
it exactly like a standalone checkbox** — find the cell control and call
`setChecked()` / `isChecked()` / `click()`.  No separate table API is needed.

For a Lazarus **TCheckListBox** (the results area of this app) the rows are
ordinary list items that each carry a check flag — i.e. a list of checkable
rows.  Address each "cell" as a normal list row and read/toggle its flag:

```java
WinWindow table = main.child(By.className("LCLCheckListBox"));
table.isCheckListBox();          // true
table.itemCount();               // number of rows
table.itemTexts();               // row texts
table.itemCheckState(0);         // check flag of row 0
table.isItemChecked(0);          // true if row 0 checked
table.setItemChecked(0, true);   // set flag
table.clickItemCheck(0);         // toggle flag
```

> Note: `LCLCheckListBox` stores its row-check flags internally (Lazarus
> `TCheckListBox`); the standard `LB_GETCHECK`/`LB_SETCHECK` messages are what
> the generic helpers use and may not reflect the widget's own check state for
> that particular control.  The row text/selection helpers work the same as for
> a plain list box.  For a plain grid control, the checkbox cell is a child
> `Button` handled identically to a standalone checkbox.

## Menus

A window **menu bar is not a child window** — it belongs to the frame, so it is
NOT found with `By` / `child()`.  Read it from the window's menu bar:

```java
main.hasMenu();              // true if the window has a menu bar
main.menuItems();            // top-level item texts: e.g. [Файл, Настройки, Помощь]
```

A menu item behaves like a command button (clicking it runs a command), but it
is not a control with `BM_CLICK`; you address the menu bar directly.  Sub-menus
and command items can be expanded the same way (enumerator over the menu bar).

## Notes / gotchas

- **Typing** uses `SendMessage(WM_CHAR)` (not `keybd_event`) so it works from a
  background JVM and updates the control's `OnChange`.  `SetWindowTextW` /
  `EM_REPLACESEL` alone do NOT trigger LCL `OnChange`.
- **Reading Edit text** uses `SendMessage(WM_GETTEXT)`, not `GetWindowText`,
  because for a control owned by another process `GetWindowText` may return a
  cached value.
- **Clicking** uses `SendMessageTimeoutW(BM_CLICK)` so a busy UI thread (e.g. a
  synchronous search) never hangs the caller.
- **Screenshots** use `PrintWindow` + `GetDIBits` because `java.awt.Robot` may
  be denied in a non-interactive (forked/CI) session.
- Always scope to the correct PID: `WinProcess` filters top-level windows by
  its own PID, so you won't pick up a stray window from a duplicate instance.
