# Design options and decisions

The screens of One CommandCenter and OneAgentStudio were designed as options on a design canvas and one was picked
for each, before it was built. This folder keeps them so that everyone works from the same picture: an image of each
option (`images/`) and its HTML source (`source/`, open it in a browser). The built screens follow the picked
option; where the code went further (later rounds), the code and the docs in `docs/` are the reference.

**Picked** marks the option that was chosen. Options not picked stay here to show what was considered.


## 12 Nov: items 1, 3, 8, 12: text size, menu levels, page header, logo

| Option | What it is | Picked |
| --- | --- | --- |
| [Main](images/Main.png) ([source](source/Main.html)) | Console A: compact, like AA | **Picked** |
| [ConsoleB](images/ConsoleB.png) ([source](source/ConsoleB.html)) | Console B: balanced |  |
| [ConsoleC](images/ConsoleC.png) ([source](source/ConsoleC.html)) | Console C: densest |  |

## 12 Nov: item 5, the Create dialog

| Option | What it is | Picked |
| --- | --- | --- |
| [CreateA](images/CreateA.png) ([source](source/CreateA.html)) | Create A: one dialog, three panes | **Picked** |
| [CreateB](images/CreateB.png) ([source](source/CreateB.html)) | Create B: two short steps |  |

## 12 Nov: item 6, the agent editor

| Option | What it is | Picked |
| --- | --- | --- |
| [StudioA](images/StudioA.png) ([source](source/StudioA.html)) | Editor A: text buttons, Common / All | **Picked** |
| [StudioB](images/StudioB.png) ([source](source/StudioB.html)) | Editor B: icon and text buttons, Everyday / All toolkits |  |

## 13 Nov list, items 12 + 10 + 1: logo and name, menu headings vs pages, small status tiles

| Option | What it is | Picked |
| --- | --- | --- |
| [Brand1](images/Brand1.png) ([source](source/Brand1.html)) | Brand + menu 1: light mark + product name; muted headings |  |
| [Brand2](images/Brand2.png) ([source](source/Brand2.html)) | Brand + menu 2: company logo / product; heading band |  |
| [Brand3](images/Brand3.png) ([source](source/Brand3.html)) | Brand + menu 3: one-line name; accent line |  |

## 13 Nov list, items 2 + 4: Public and Private one layout; Create dialog v2

| Option | What it is | Picked |
| --- | --- | --- |
| [AutoA](images/AutoA.png) ([source](source/AutoA.html)) | Automation A: tree picks the folder, list shows what is in it |  |
| [AutoB](images/AutoB.png) ([source](source/AutoB.html)) | Automation B: folders in the list, tree folded away |  |
| [CreateV2](images/CreateV2.png) ([source](source/CreateV2.html)) | Create v2: templates left; Start from, Name, Save in right | **Picked** |

## 13 Nov list, items 5 + 6 + 7: enterprise buttons, calmer toolkits, one tab style

| Option | What it is | Picked |
| --- | --- | --- |
| [EditorX](images/EditorX.png) ([source](source/EditorX.html)) | Editor X: one filled button, neutral tools, grey toolkits, underline tabs |  |
| [EditorY](images/EditorY.png) ([source](source/EditorY.html)) | Editor Y: outlined buttons, toolbar band, tinted toolkit icons, boxed tabs |  |

## 13 Nov round 2, item 1: Home status tiles (agents in the list now show the agent icon, item 4)

| Option | What it is | Picked |
| --- | --- | --- |
| [HomeH1](images/HomeH1.png) ([source](source/HomeH1.html)) | Home H1: two labelled strips of six equal cells, no icons | **Picked** |
| [HomeH2](images/HomeH2.png) ([source](source/HomeH2.html)) | Home H2: cards, six per row, label + number + one hint (AA dashboard style) |  |
| [HomeH3](images/HomeH3.png) ([source](source/HomeH3.html)) | Home H3: runs as one status bar; platform figures on one line |  |

## 13 Nov round 2, items 2 + 3: left menu (headings vs pages, everything reachable); the same menu in Studio, beside the page not over it

| Option | What it is | Picked |
| --- | --- | --- |
| [MenuM1](images/MenuM1.png) ([source](source/MenuM1.html)) | Menu M1: AA-like groups with icons, one open at a time, rail + fly-outs when collapsed | **Picked** |
| [MenuM2](images/MenuM2.png) ([source](source/MenuM2.html)) | Menu M2: group rail + page panel (two levels, nothing scrolls) |  |
| [MenuM3](images/MenuM3.png) ([source](source/MenuM3.html)) | Menu M3: everything open, compact rows, group bands |  |

## 13 Nov round 2, item 6: Activity table and standard column names for every table

| Option | What it is | Picked |
| --- | --- | --- |
| [ActA6](images/ActA6.png) ([source](source/ActA6.html)) | Activity A6: one plain list, standard names, no date rows | **Picked** |
| [ActA6B](images/ActA6B.png) ([source](source/ActA6B.html)) | Activity A6-B: same, plus an optional Group by (off by default) |  |

## 13 Nov round 3, items 4 + 5 + 7 + 8: logo and company name on the top bar, one-line page descriptions with Docs/Video, the menu fold

| Option | What it is | Picked |
| --- | --- | --- |
| [TopT1](images/TopT1.png) ([source](source/TopT1.html)) | Top bar T1: ☰ fold, logo on plate, name centred; description inline | **Picked** |
| [TopT2](images/TopT2.png) ([source](source/TopT2.html)) | Top bar T2: lightened logo, name before the buttons; « at the menu foot; description below |  |
| [TopT3](images/TopT3.png) ([source](source/TopT3.html)) | Top bar T3: lightened logo, name centred in capitals; "« Hide menu" line |  |

## 25 Nov: OneChat flow model in Studio (Part 1, step 7): mode, connection preset, fallback, training feedback

| Option | What it is | Picked |
| --- | --- | --- |
| [OCStudioA](images/OCStudioA.png) ([source](source/OCStudioA.html)) | OneChat A: Studio's assistant, mode chosen per request | **Picked** |
| [OCStudioB](images/OCStudioB.png) ([source](source/OCStudioB.html)) | OneChat B: mode set once in Settings, shown in the dialog |  |
| [OCSettings](images/OCSettings.png) ([source](source/OCSettings.html)) | OneChat: Settings > Studio assistant, and the connection preset |  |

## 26 Nov: menu in five sections (Setup, Build, Run, Manage, Monitor), no Home; the new Install and set up page

| Option | What it is | Picked |
| --- | --- | --- |
| [MenuS1](images/MenuS1.png) ([source](source/MenuS1.html)) | Menu S1: five sections; Setup as one checklist with a live check per step |  |
| [MenuS2](images/MenuS2.png) ([source](source/MenuS2.html)) | Menu S2: five sections; Setup in four phases with progress cards | **Picked** |

## 26 Nov: Workflows: built in Studio like AA's process editor (plus our own steps), runs followed in Run > Workflow runs

| Option | What it is | Picked |
| --- | --- | --- |
| [WfA](images/WfA.png) ([source](source/WfA.html)) | Workflow A: diagram with branches, step list on the left, settings on the right | **Picked** |
| [WfB](images/WfB.png) ([source](source/WfB.html)) | Workflow B: lanes for who does each step (a second view of the same flow) |  |
| [WfRuns](images/WfRuns.png) ([source](source/WfRuns.html)) | Workflow runs (Run section): list, live steps, remind / reassign / skip |  |

## Keeping it up to date

New options are drawn on the design canvas first, then one is picked and built. Add the new boards here (image and
source) in the same change that builds the picked one, and mark it **Picked**.
