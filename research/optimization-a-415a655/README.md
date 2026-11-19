# Этап A: воспроизводимые проверки

Baseline: `415a6552eb0cc3d9cdff4a59d164b98f98049a47`, Mindustry v160.7 / Arc 7445105cd2. План до правок — [OPTIMIZATION_A_PLAN](../../docs/OPTIMIZATION_A_PLAN.md); итог — [OPTIMIZATION_A_RESULTS](../../docs/OPTIMIZATION_A_RESULTS.md).

Эта папка не входит в sourceSets рабочего мода. [prepare.py](prepare.py) распаковывает исходный коммит в `.gradle/optimization-a-415a655/snapshot`, копирует текущие производственные исходники, проверяет точное совпадение неизменённого UnitProjection с baseline и допустимую правку GroupsHud. Затем **только в snapshot** создаёт варианты адаптера и переключаемые clip-границы. В рабочем JAR нет этих классов, флагов, shader-тестов и измерителей.

Сохранения/настройки клиента создаются отдельно внутри snapshot. BuildWheel копируется с исключением `.git` и `tests`; исходник другого мода не меняется. Нужны Python 3, полный JDK 17, desktop.jar v160.7 и доступные зависимости Gradle.

```powershell
python research/optimization-a-415a655/prepare.py
$env:ORG_GRADLE_PROJECT_mindustryDesktopJar='C:/Program Files (x86)/Steam/steamapps/common/Mindustry/jre/desktop.jar'
$env:ORG_GRADLE_PROJECT_mindustryJava='C:/Program Files/Eclipse Adoptium/jdk-17.0.15.6-hotspot/bin/java.exe'
$env:ORG_GRADLE_PROJECT_buildWheelDirectory='C:/Program Files (x86)/Steam/steamapps/common/Mindustry/saves/mods/BuildWheel'
$env:ORG_GRADLE_PROJECT_stageAReports="$PWD/research/optimization-a-415a655/repeat-new"
./gradlew.bat -p .gradle/optimization-a-415a655/snapshot stageA --offline
```

Режимы через `-PstageAMode=...`:

- `all` (default): isolated/no-GL/GPU таймеры, пиксели и custom contexts, HUD трёх размеров и последовательные фазы клиента.
- `screening`: кандидаты heat-hoist, color-hoist и raw alpha-skip. Последний не сертифицирует весь GL-контекст и **не является готовой безопасной production-оптимизацией**.
- `clipping`: один и тот же production-адаптер, старые границы → удаление только post-flush → удаление обеих → обратно; три окна, нагрев 0/0,5/1, полная HUD-матрица и ordered-state proof.
- `timing`: тот же clipping-эксперимент в 1280×900. В последней версии время измеряется на настоящем игровом **arc.graphics.g2d.SpriteBatch**, а Counting/GL proxy выполняют отдельные диагностические проходы.

Не запускать подготовку во время компиляции/работы предыдущего snapshot. Не запускать параллельно ресурсоёмкие проверки при измерении. Клиент использует скрытое настоящее SDL/OpenGL-окно; других ОС/устройств это не имитирует.

```powershell
python research/optimization-a-415a655/summarize.py
./gradlew.bat build smoke --offline --rerun-tasks
./gradlew.bat benchmark projectionBenchmark --offline
```

Root-задачи проверяют именно рабочий мод, без исследовательских патчей. Первые три переменные окружения нужны smoke; исследовательский `stageAReports` ему не нужен.

## Кандидаты и границы

Генерируемые Before/Heat/Colors/RawSkipProjection получаются из исходного адаптера текстовыми локальными заменами; сортировка, позы, геометрия и масштабирование не переписываются. В snapshot снят final с базового класса. Копии имеют иные классы/JIT/расположение полей, поэтому ранние различия времени Before vs production при одинаковом теле **не считаются эффектом E**. В финальном clipping-сравнении оба режима используют один экземпляр production-адаптера.

Тело GroupsHud в snapshot — baseline с флагами только для границ: baseline, post-only и both. Подготовка проверяет, что соответствующая ветка точно моделирует рабочую правку. Производственный post-only не содержит этих веток или состояния. Пиксельный oracle использует исходный BeforeProjection, а производственный рендер/кандидаты получают те же настоящие Unit/профили.

Непустые GPU-отправки, blend, texture/scissor, quad и empty flush считаются отдельно. Диагностика выключена во временных блоках. Окончательные HUD-замеры используют plain игровой SpriteBatch без Counting-подкласса: это дополнительный контроль влияния инструментации. Shader в проверке caller context и apply-счётчик в nested-проверке — только испытание совместимости существующего рендера, не новый backend.

## Реестр запусков

| Каталог | Что проверяет / ограничения |
| --- | --- |
| screening-1 | Отбор S/J на настоящих ресурсах, method-handle prepare, GL/no-GL, полный HUD. E здесь означает удаление обеих границ. |
| final-1 | Предварительный расширенный E (both), 480 projection-кейсов, custom blend/shader/equation, выбранные nested clips. До расширения всех HUD heat/resize случаев. |
| final-2 | Расширенный E (both): 1063 точных сравнений production; 432 HUD-кейса + 144 ordered scissor/state, 3 отрицательных S-пробы. |
| clipping-1 | Финальный post-only: 576 точных сравнений, 144 ordered-state; прямой/обратный отбор baseline/post/both с одним адаптером. Время ещё на Counting с выключенным счётчиком. |
| native-1 | Контроль на plain игровом SpriteBatch, один адаптер и одно окно. Окончательный контроль временных выводов; 192 точных сравнения, 48 ordered-state. |
| validation | Сборка, 69 JUnit-тестов и Root SDL/OpenGL smoke принятой производственной версии; предыдущие benchmark/projectionBenchmark той же неизменённой модели/адаптера. |

Сырые CSV не исправляются задним числом. Последняя версия шаблонов исправила отображение дробного resize и HUD N в метаданных: в ранних `final-*` поле N у HUD было ёмкостью 11, а реальное количество определяется config (0–11 и 24–35: 1 сохранённая + нераспределённая; остальные: 10 + нераспределённая). Раннее поле P в HUD-пикселях округляло resize 0,25 до 0; точные значения восстанавливаются по config/16 (0,25 / 1 / 3). В новых данных поле P для HUD-пикселей содержит resize, а для isolated-пикселей — размер по hitSize. В HUD-времени P=0: это несколько разных размеров, единого P нет.

В `final-1/2` фазы кадра подтверждают работоспособность и непустоту HUD, но рендеры имели разные классы. Их колебания, как и смену частот/фоновых задач, нельзя приписывать E. Отдельная ранняя smoke-таблица baseline/HUD последовательна и тоже не доказывает ускорения.

## Матрицы и метрики

Isolated: Conquer/Oct/Disrupt, нагрев 0/0,5/1, 1/11, basic/full, P=64, A–B–B–A; дополнительные P=16/150, alpha 1/0,32. Для качества также Reign/Eclipse, восемь состояний, P=16/64/150, два alpha, прозрачный/серый фон: 480 сравнений на принятый адаптер.

HUD: 48 выбранных сочетаний четырёх направлений, UI-scale 0,75/1/2, 1/10 сохранённых + нераспределённая, internal/external, всех трёх режимов распределения, fractional position, expansion 0,4/1, редактор/обычный HUD, occupancy 0/100/300%. В final-2 и далее повторено при heat 0/0,5/1 (144 на окно) и per-type resize 25/100/300% по трём частям набора. Это выбранная матрица, **не полный декартов перебор всех факторов**. HUD-время сравнивается при resize=100%.

Nested proof: config-23/47, три heat, обычный shader и чужой shader с alpha из текстуры и `apply()`-счётчиком оттенка. Parent clip — полное fractional окно, частичное пересечение, disjoint, а также content.width=0,4 с округлением до нуля. Pending caller quad рисуется до HUD. Для каждого сравнения проверяется весь RGBA и последовательность count/scissor/enabled/blend factors/equations/program/texture каждой реальной draw. Это доказывает сохранение фактических отправок в проверенных сценариях, а не только итоговой картинки.

- `times.csv`: p50/p95/p99/min/max CPU, завершение блока с финальным glFinish на пакет, аллокации текущего Java-потока. Очистка FBO входит в GL CPU-пакет. HUD-блок — 200 прогревочных + 256 измеренных.
- `gpu.csv`: TIME_ELAPSED, 64 query, чтение готовых unsigned результатов вне измерения; Core.gl30 остаётся null, VertexArray не заменяется. Это timeline, не GPU busy; CPU с GPU не складывать.
- `counts.csv`: отдельно quad/zero-alpha/запросы flush/непустые/реальные GL draw/texture/scissor. Контрольные isolated-проекции не менялись, их различия времени — шум/класс/JIT, не эффект изменения HUD.
- `pixels.csv`: число отличающихся пикселей и максимальная ошибка RGB/alpha (0–255). `raw-alpha-skip` в caller contexts — намеренно отвергаемый эксперимент; несовпадения сохраняются.
- `clip-sequences.csv`: точная последовательность реальных draw и перечисленных GL-состояний совпала; проверки идут только вне тайминга.
- `prepare.csv`: 32 вызова MethodHandle на образец, 2000 образцов, ns/call; не весь CPU draw и не GPU.
- `empty-flush.csv`: отдельный контроль пустого штатного Draw.flush и одинакового счётного цикла; разность — ориентир цены вызова, **не измеренный выигрыш HUD**.
- `frames.csv`: 120 кадров после 50 прогревочных, paused 500/1000, native commandMode всегда true, mode 0 выключает только draw HUD. Без SDL swap/presentation; не FPS и не подсчёт показанных кадров.

Температура, частоты, фоновые процессы, paging и энергия не контролировались. Большой разброс и единовременные аллокации сообщаются, не маскируются средними. Изменение E не создаёт объектов, не читает новые состояния GL и не добавляет обходов мира. Настоящий бой, мультиплеер и другие GPU/ОС остаются вне этих автоматических проверок.
