"""Builds docs/minecolonies/ from the local MineColonies clone in ./minecolonies.

Refresh: git -C minecolonies pull && python3 tools/index_minecolonies.py
Reads the mod's registries (buildings, modules, jobs) and its source, and writes
indexes so an agent can find a feature's files and numbers without searching.
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MOD = ROOT / "minecolonies"
SRC = MOD / "src/main/java"
PKG = SRC / "com/minecolonies"
OUT = ROOT / "docs/minecolonies"


def read(p):
    return p.read_text(encoding="utf-8", errors="replace")


def rel(p):
    return str(p.relative_to(ROOT))


def link(p):
    # docs/minecolonies/x.md -> ../../minecolonies/...
    return f"[{p.stem}](../../{rel(p)})"


# simple class name -> file (first wins; nested classes resolved by their outer name)
FILES = {}
for f in sorted(SRC.rglob("*.java")):
    FILES.setdefault(f.stem, f)


def file_of(cls):
    return FILES.get(cls.split(".")[0])


def statements(text, start):
    """Splits text into ';'-terminated statements that start with `start`."""
    out = []
    for m in re.finditer(re.escape(start), text):
        end = text.find(";", m.start())
        # registry entries span lambdas; stop at the createXEntry());
        close = re.search(r"create\w+Entry\(\)\);", text[m.start():])
        if close and close.start() < 4000:
            end = m.start() + close.end()
        out.append(text[m.start():end])
    return out


# --- ids ---
building_ids = dict(re.findall(r'String (\w+_ID)\s*=\s*"(\w+)"',
                               read(PKG / "api/colony/buildings/ModBuildings.java")))
job_ids = dict(re.findall(r'ResourceLocation (\w+_ID)\s*=\s*new ResourceLocation\([^,]+,\s*"(\w+)"\)',
                          read(PKG / "api/colony/jobs/ModJobs.java")))

# --- jobs: field -> (id, Job class) ---
jobs = {}
for s in statements(read(PKG / "apiimp/initializer/ModJobsInitializer.java"), "ModJobs."):
    m = re.match(r"ModJobs\.(\w+)\s*=\s*register\([^,]+,\s*ModJobs\.(\w+)", s)
    p = re.search(r"setJobProducer\((\w+)::new", s)
    if m and p:
        jobs[m.group(1)] = (job_ids.get(m.group(2), m.group(2)), p.group(1))


def ai_of(job_cls):
    """The AI class a job builds, looking up its superclasses (huscarl uses knight's)."""
    for cls in [job_cls] + superclasses(job_cls):
        f = file_of(cls)
        # guards build theirs in generateGuardAI
        m = f and re.search(r"generate(?:Guard)?AI\s*\(\s*\)\s*\{\s*return\s+new\s+(\w+)", read(f))
        if m:
            return m.group(1)
    return None


# --- guard types: field -> (job field, skills) ---
guards = {}
for s_ in statements(read(PKG / "apiimp/initializer/ModGuardTypesInitializer.java"), "ModGuardTypes."):
    m = re.match(r"ModGuardTypes\.(\w+)\s*=", s_)
    j = re.search(r"ModJobs\.(\w+)\.get\(\)", s_)
    if m and j:
        guards[m.group(1)] = (j.group(1), re.findall(r"Skill\.(\w+)", s_))

# --- modules: CONST -> (name, classes, job field, skills) ---
modules = {}
mtext = read(PKG / "core/colony/buildings/modules/BuildingModules.java")
for m in re.finditer(r"ModuleProducer<[^=]*?>\s+(\w+)\s*=\s*(.*?);\s*\n", mtext, re.S):
    body = m.group(2)
    name = re.search(r'"(\w+)"', body)
    cls = re.findall(r"new ([\w.]+)\(", body)
    job = re.search(r"ModJobs\.(\w+)\.get\(\)", body)
    skills = re.findall(r"Skill\.(\w+)", body)
    guard = re.search(r"ModGuardTypes\.(\w+)\.get\(\)", body)
    if guard and guard.group(1) in guards:
        job = re.match(r"(\w+)", guards[guard.group(1)][0])
        skills = guards[guard.group(1)][1]
    modules[m.group(1)] = (name.group(1) if name else m.group(1).lower(),
                           [c for c in cls if c != "BuildingEntry.ModuleProducer"],
                           job.group(1) if job else None, skills)

# --- buildings ---
buildings = []
for s in statements(read(PKG / "apiimp/initializer/ModBuildingsInitializer.java"), "ModBuildings."):
    m = re.match(r"ModBuildings\.\w+\s*=\s*DEFERRED_REGISTER\.register\(ModBuildings\.(\w+)", s)
    if not m:
        continue
    bid = building_ids.get(m.group(1), m.group(1))
    prod = re.search(r"setBuildingProducer\(([\w.]+)::new", s)
    block = re.search(r"setBuildingBlock\(ModBlocks\.(\w+)", s)
    mods = re.findall(r"addBuildingModuleProducer\((\w+)", s)
    buildings.append((bid, prod.group(1) if prod else "?", block.group(1) if block else "?", mods))

# --- data dirs by job id ---
DATA = [MOD / "src/main/resources/data/minecolonies", MOD / "src/datagen/generated/minecolonies/data/minecolonies"]


def crafter_dirs(job_id):
    return [d for base in DATA for d in [base / "crafterrecipes" / job_id] if d.is_dir()]


def recipe_file(bid):
    names = {f"blockhut{bid}.json", f"blockhut{bid.replace('_', '')}.json"}
    for base in DATA:
        for f in (base / "recipes").rglob("blockhut*.json") if (base / "recipes").is_dir() else []:
            if f.name in names:
                return f
    return None


# --- constants ---
CONST = re.compile(r"static\s+final\s+(int|long|double|float|boolean|String)\s+(\w+)\s*=\s*([^;]+);")


def constants(f):
    text = read(f)
    out = []
    for m in CONST.finditer(text):
        before = text[max(0, m.start() - 600):m.start()]
        before = re.sub(r"\s*(?:@\w+\s*)*(?:public|private|protected)?\s*$", "", before)
        doc = ""
        i = before.rfind("/**")
        if i >= 0 and before.endswith("*/"):
            doc = " ".join(l.strip(" *") for l in before[i + 3:-2].splitlines() if not l.strip(" *").startswith("@"))
            doc = re.sub(r"\s+", " ", doc).strip()
        line = text.count("\n", 0, m.start()) + 1
        val = re.sub(r"\s+", " ", m.group(3).strip())
        out.append((m.group(2), val, line, doc))
    return out


def superclasses(cls, stop=("Object",)):
    chain = []
    while cls and cls not in stop:
        f = file_of(cls)
        if not f:
            break
        m = re.search(rf"class\s+{re.escape(cls.split('.')[-1])}\b([^{{]*)", read(f))
        head = m.group(1) if m else ""
        while "<" in head and re.search(r"<[^<>]*>", head):
            head = re.sub(r"<[^<>]*>", "", head)
        m = re.search(r"extends\s+([\w.]+)", head)
        if not m:
            break
        cls = m.group(1).split(".")[-1]
        if cls in chain:
            break
        chain.append(cls)
    return chain


# --- write huts.md ---
commit = subprocess.run(["git", "-C", str(MOD), "log", "-1", "--format=%h %cs"], capture_output=True, text=True).stdout.strip()
OUT.mkdir(parents=True, exist_ok=True)
const_files = []  # ordered, unique
hl = [f"# MineColonies huts index\n\nGenerated by `tools/index_minecolonies.py` from minecolonies@{commit}. "
      "Paths link into the local clone at `minecolonies/`.\n"
      "Skills are (primary, secondary) as the work module declares them; crafting modules list the recipe skills too.\n"]
for bid, bcls, block, mods in sorted(buildings):
    bf = file_of(bcls)
    hl.append(f"\n## {bid}\n")
    hl.append(f"- Building: {link(bf) if bf else bcls} (block `{block}`)")
    r = recipe_file(bid)
    if r:
        hl.append(f"- Hut recipe: [{r.name}](../../{rel(r)})")
    if bf:
        const_files.append(bf)
    job_skills = {}  # job field -> skills, in module order; a hut can declare skills on any of its modules
    mod_names = []
    for mc in mods:
        name, classes, job, skills = modules.get(mc, (mc.lower(), [], None, []))
        mod_names.append(name)
        if job:
            ks = job_skills.setdefault(job, [])
            ks += [k for k in skills if k not in ks]
    for job, skills in job_skills.items():
        jid, jcls = jobs.get(job, (job, None))
        jf = file_of(jcls) if jcls else None
        ai = ai_of(jcls) if jcls else None
        af = file_of(ai) if ai else None
        sk = f" skills {', '.join(skills)}" if skills else ""
        hl.append(f"- Job `{jid}`:{sk}")
        if jf:
            chain = superclasses(jcls)
            hl.append(f"  - Job class: {link(jf)}" + (f" extends {' → '.join(chain[:3])}" if chain else ""))
            const_files.append(jf)
        if af:
            chain = superclasses(ai)
            hl.append(f"  - AI: {link(af)}" + (f" extends {' → '.join(chain[:4])}" if chain else ""))
            const_files.append(af)
        for d in crafter_dirs(jid):
            hl.append(f"  - Crafter recipes: [{d.name}/](../../{rel(d)}) ({len(list(d.glob('*.json')))} files)")
    hl.append(f"- Modules: {', '.join(mod_names)}")
(OUT / "huts.md").write_text("\n".join(hl) + "\n")

# --- write constants.md: per-feature file, plus shared AI bases and constant holders ---
shared = [FILES[c] for c in ("AbstractEntityAIBasic", "AbstractEntityAIInteract", "AbstractEntityAISkill",
                             "AbstractEntityAICrafting", "AbstractEntityAIGuard", "AbstractEntityAIStructure",
                             "AbstractEntityAIHerder", "AbstractBuilding", "AbstractBuildingGuards",
                             "AbstractBuildingStructureBuilder",
                             # colony-wide systems listed in docs/minecolonies/README.md
                             "Colony", "ColonyManager", "CitizenManager", "CitizenData", "EntityCitizen",
                             "CitizenHappinessHandler", "HappinessRegistry", "CitizenFoodHandler",
                             "CitizenDiseaseHandler", "CitizenSleepHandler", "CitizenSkillHandler",
                             "CitizenExperienceHandler", "EntityAICitizenWander", "EntityAIEatTask",
                             "EntityAISickTask", "EntityAISleep", "EntityAIMournCitizen", "EntityAIVisitor",
                             "RaidManager", "AbstractShipRaidEvent", "HordeRaidEvent", "ReproductionManager",
                             "VisitorManager", "TravellingManager", "GraveManager", "ColonyPackageManager",
                             "WorkManager", "StandardRequestManager", "ResearchManager", "QuestManager",
                             "Permissions", "ColonyPermissionEventHandler", "TileEntityRack",
                             "TileEntityColonyBuilding", "ItemSupplyChestDeployer", "ItemSupplyCampDeployer",
                             "FoodUtils", "ServerConfiguration") if c in FILES]
shared += sorted((PKG / "api/util/constant").glob("*.java"))
cl = [f"# MineColonies constants\n\nGenerated by `tools/index_minecolonies.py` from minecolonies@{commit}. "
      "`static final` numbers, booleans and strings, with the first line of their javadoc. "
      "Line numbers link into `minecolonies/`. Check the source before trusting a value's meaning: some constants are unused.\n"]
done = set()
for f in const_files + shared:
    if f in done:
        continue
    done.add(f)
    cs = [c for c in constants(f) if c[1] != '""']
    if not cs:
        continue
    cl.append(f"\n## {f.stem}\n\n{link(f)}\n")
    for name, val, line, doc in cs:
        val = val if len(val) < 80 else val[:77] + "..."
        cl.append(f"- L{line} `{name} = {val}`" + (f" — {doc[:120]}" if doc else ""))
(OUT / "constants.md").write_text("\n".join(cl) + "\n")

# --- write classes.md: every package and its classes, with the first javadoc sentence ---
pl = [f"# MineColonies class map\n\nGenerated by `tools/index_minecolonies.py` from minecolonies@{commit}. "
      "Every package under `com.minecolonies` with its classes and the first sentence of each class javadoc.\n"]
by_pkg = {}
for f in sorted(PKG.rglob("*.java")):
    if f.name == "package-info.java":
        continue
    by_pkg.setdefault(f.parent, []).append(f)
CLASSDECL = re.compile(r"^(?:public\s+|abstract\s+|final\s+)*(?:class|interface|enum|record|@interface)\s", re.M)
for pkg, fs in sorted(by_pkg.items()):
    pl.append(f"\n## [{pkg.relative_to(SRC).as_posix().replace('/', '.')}](../../{rel(pkg)})\n")
    for f in fs:
        text = read(f)
        m = CLASSDECL.search(text)
        doc = ""
        i = text.rfind("/**", 0, m.start()) if m else -1
        j = text.find("*/", i)
        # the javadoc belongs to the class only if nothing but annotations sits between them
        if i >= 0 and all(l.strip().startswith("@") for l in text[j + 2:m.start()].splitlines() if l.strip()):
            doc = " ".join(l.strip(" *") for l in text[i + 3:j].splitlines() if not l.strip(" *").startswith("@"))
            doc = re.split(r"(?<=\.)\s", re.sub(r"\s+", " ", doc).strip())[0]
        pl.append(f"- {f.stem}" + (f" — {doc[:120]}" if doc else ""))
(OUT / "classes.md").write_text("\n".join(pl) + "\n")

print(f"{len(buildings)} buildings, {len(jobs)} jobs, {len(modules)} modules, {len(done)} constant files", file=sys.stderr)
