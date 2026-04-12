#!/usr/bin/env python3
"""
seed_all_grades.py
------------------
Seeds Firestore with the complete grade → subject → chapter tree for classes 6–10.

Collections written (idempotent – uses set() with merge):
  subjects/   – one doc per (grade × subject), e.g. math_9th, science_8th
  chapters/   – one doc per chapter, keyed by subject_id + order

NCERT PDF URLs are embedded directly where book codes are confirmed:
  Grade 6  → math (femh1), science (fesc1)   [pattern-extrapolated]
  Grade 7  → math (gemh1), science (gesc1)   [pattern-extrapolated]
  Grade 8  → math (hemh1), science (hesc1), english (cesa1)
  Grade 9  → math (iemh1), science (iesc1)
  Grade 10 → math (jemh1), science (jesc1)

Run:
  pip install firebase-admin
  python seed_all_grades.py
"""

import os
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).parent
sys.path.insert(0, str(SCRIPT_DIR / "fastapi server"))

import firebase_admin
from firebase_admin import credentials, firestore

SA_PATH = os.environ.get(
    "FIREBASE_SERVICE_ACCOUNT",
    str(SCRIPT_DIR / "firebase_serviceaccount.json"),
)

if not os.path.exists(SA_PATH):
    print(f"ERROR: Service-account file not found:\n  {SA_PATH}")
    sys.exit(1)

cred = credentials.Certificate(SA_PATH)
firebase_admin.initialize_app(cred)
db = firestore.client()

NCERT_PDF = "https://ncert.nic.in/textbook/pdf/"


def pdf(code: str, ch: int) -> str:
    return f"{NCERT_PDF}{code}{ch:02d}.pdf"


# ══════════════════════════════════════════════════════════════════════════════
# CHAPTER DATA
# Each entry: (title, topic_tags, estimated_minutes)
# Book codes used for NCERT PDF URL generation.
# ══════════════════════════════════════════════════════════════════════════════

# ─── Class 6 ────────────────────────────────────────────────────────────────

MATH_6 = [
    ("Knowing Our Numbers",             ["numbers", "large numbers", "estimation"],          30),
    ("Whole Numbers",                   ["numbers", "whole numbers", "number line"],         25),
    ("Playing with Numbers",            ["factors", "multiples", "HCF", "LCM"],             30),
    ("Basic Geometrical Ideas",         ["geometry", "points", "lines", "angles"],           25),
    ("Understanding Elementary Shapes", ["geometry", "shapes", "angles", "polygons"],        25),
    ("Integers",                        ["integers", "negative numbers", "number line"],     30),
    ("Fractions",                       ["fractions", "equivalent fractions", "mixed"],      35),
    ("Decimals",                        ["decimals", "place value", "operations"],           30),
    ("Data Handling",                   ["data", "bar graph", "pictograph", "mean"],         25),
    ("Mensuration",                     ["mensuration", "perimeter", "area"],                30),
    ("Algebra",                         ["algebra", "variables", "expressions"],             30),
    ("Ratio and Proportion",            ["ratio", "proportion", "unitary method"],           25),
    ("Symmetry",                        ["symmetry", "lines of symmetry", "reflection"],     20),
    ("Practical Geometry",              ["geometry", "compass", "ruler", "construction"],    20),
]  # book code: femh1

SCI_6 = [
    ("Food: Where Does It Come From?",          ["food", "plants", "animals", "sources"],          20),
    ("Components of Food",                       ["nutrition", "proteins", "vitamins", "diet"],     25),
    ("Fibre to Fabric",                          ["fibre", "cotton", "wool", "fabric"],             20),
    ("Sorting Materials into Groups",            ["materials", "properties", "classification"],     20),
    ("Separation of Substances",                 ["separation", "filtration", "sedimentation"],     25),
    ("Changes Around Us",                        ["changes", "reversible", "irreversible"],         20),
    ("Getting to Know Plants",                   ["plants", "root", "stem", "leaf", "flower"],      25),
    ("Body Movements",                           ["joints", "bones", "muscles", "movement"],        20),
    ("The Living Organisms and Their Surroundings", ["habitat", "adaptation", "organisms"],         25),
    ("Motion and Measurement of Distances",      ["motion", "measurement", "units", "distance"],    25),
    ("Light, Shadows and Reflections",           ["light", "shadow", "transparent", "reflection"],  20),
    ("Electricity and Circuits",                 ["electricity", "circuit", "conductor"],           25),
    ("Fun with Magnets",                         ["magnet", "poles", "magnetic force"],             20),
    ("Water",                                    ["water", "water cycle", "evaporation"],           20),
    ("Air Around Us",                            ["air", "atmosphere", "oxygen", "nitrogen"],       20),
    ("Garbage In, Garbage Out",                  ["waste", "recycling", "landfill", "compost"],     20),
]  # book code: fesc1

SOCIAL_6 = [
    # History — Our Pasts I (ch 1–9)
    ("What, Where, How and When?",                      ["history", "sources", "timeline"],            20),
    ("On the Trail of the Earliest People",             ["prehistory", "hunter-gatherer", "tools"],    25),
    ("From Gathering to Growing Food",                  ["agriculture", "neolithic", "settlements"],   25),
    ("In the Earliest Cities",                          ["Harappan", "Indus Valley", "cities"],        30),
    ("What Books and Burials Tell Us",                  ["Vedas", "burial", "archaeology"],             25),
    ("Kingdoms, Kings and an Early Republic",           ["kingdoms", "mahajanapadas", "republic"],     25),
    ("New Questions and Ideas",                         ["Buddhism", "Jainism", "philosophy"],         25),
    ("Ashoka, The Emperor Who Gave Up War",             ["Ashoka", "Maurya", "dhamma"],                25),
    ("Vital Villages, Thriving Towns",                  ["agriculture", "trade", "guilds"],            25),
    # Geography — The Earth: Our Habitat (ch 10–17)
    ("The Earth in the Solar System",                   ["solar system", "planets", "orbit"],          20),
    ("Globe: Latitudes and Longitudes",                 ["latitude", "longitude", "globe"],            25),
    ("Motions of the Earth",                            ["rotation", "revolution", "seasons"],         25),
    ("Maps",                                            ["maps", "symbols", "scale", "compass"],       20),
    ("Major Domains of the Earth",                      ["lithosphere", "hydrosphere", "atmosphere"],  25),
    ("Major Landforms of the Earth",                    ["mountains", "plateaus", "plains"],           25),
    ("Our Country — India",                             ["India", "states", "rivers", "geography"],    25),
    ("India: Climate, Vegetation and Wildlife",         ["climate", "vegetation", "wildlife", "India"],25),
    # Civics — Social and Political Life I (ch 18–26)
    ("Understanding Diversity",                         ["diversity", "culture", "unity"],             20),
    ("Diversity and Discrimination",                    ["discrimination", "equality", "prejudice"],   20),
    ("What is Government?",                             ["government", "democracy", "laws"],           20),
    ("Key Elements of a Democratic Government",         ["democracy", "participation", "elections"],   20),
    ("Panchayati Raj",                                  ["panchayat", "local government", "villages"], 20),
    ("Rural Administration",                            ["rural", "patwari", "police", "courts"],      20),
    ("Urban Administration",                            ["municipal", "urban", "ward", "services"],    20),
    ("Rural Livelihoods",                               ["farming", "livelihood", "rural economy"],    20),
    ("Urban Livelihoods",                               ["urban", "jobs", "market", "services"],       20),
]

ENGLISH_6 = [
    ("A Tale of Two Birds",             ["story", "birds", "moral"],                 20),
    ("The Friendly Mongoose",           ["story", "animal", "trust", "moral"],       20),
    ("The Shepherd's Treasure",         ["story", "honesty", "wisdom"],              20),
    ("The Old-Clock Shop",              ["story", "kindness", "deaf-mute"],          20),
    ("Tansen",                          ["biography", "music", "Mughal"],            20),
    ("The Monkey and the Crocodile",    ["fable", "friendship", "cunning"],          20),
    ("The Wonder Called Sleep",         ["science", "sleep", "dreams"],              15),
    ("A Pact with the Sun",             ["story", "sunlight", "health"],             20),
    ("What Happened to the Reptiles",   ["story", "diversity", "acceptance"],        20),
    ("A Strange Wrestling Match",       ["story", "wit", "ghost"],                   20),
]

HINDI_6 = [
    ("Vah Chidiya Jo (Keshav Prasal)",             ["poetry", "freedom", "bird"],           15),
    ("Bachpan (Krishna Sobti)",                     ["prose", "childhood", "memories"],      20),
    ("Nadan Dost (Premchand)",                      ["story", "friendship", "innocence"],    20),
    ("Chand Se Thodi Si Gappe",                     ["poetry", "moon", "imagination"],       15),
    ("Aksharon Ka Mahatva",                          ["essay", "literacy", "writing"],        15),
    ("Paar Nazar Ke",                               ["story", "imagination", "science"],     20),
    ("Sathi Hath Badhana",                          ["poetry", "unity", "cooperation"],      15),
    ("Aise Aise",                                   ["play", "drama", "school"],             20),
    ("Ticket-Album",                                ["story", "hobby", "stamps"],            20),
    ("Jhansi Ki Rani (Subhadra Kumari Chauhan)",    ["poetry", "history", "bravery"],        15),
    ("Jo Dekhkar Bhi Nahi Dekhte (Helen Keller)",   ["essay", "senses", "perception"],       20),
    ("Sansar Pustak Hai (Jawaharlal Nehru)",         ["letter", "world", "learning"],         20),
    ("Main Sabse Chhoti Hoon",                      ["poetry", "childhood", "innocence"],    15),
    ("Lokgeet",                                     ["essay", "folk songs", "culture"],      20),
    ("Naukar",                                      ["story", "servant", "class"],           20),
    ("Van Ke Marg Mein (Tulsidas)",                  ["poetry", "Ram", "forest"],             15),
    ("Sans Sans Mein Bans (Arvind Gupta)",           ["essay", "bamboo", "craft"],            20),
]

# ─── Class 7 ────────────────────────────────────────────────────────────────

MATH_7 = [
    ("Integers",                        ["integers", "number line", "operations"],           30),
    ("Fractions and Decimals",          ["fractions", "decimals", "multiplication"],         35),
    ("Data Handling",                   ["mean", "median", "mode", "bar graph"],             25),
    ("Simple Equations",                ["algebra", "equations", "variables"],               30),
    ("Lines and Angles",                ["lines", "angles", "parallel", "transversal"],      30),
    ("The Triangle and Its Properties", ["triangles", "angles", "altitude", "median"],       35),
    ("Congruence of Triangles",         ["congruence", "SSS", "SAS", "ASA", "RHS"],          30),
    ("Comparing Quantities",            ["ratio", "percentage", "profit", "loss"],           35),
    ("Rational Numbers",                ["rational", "fractions", "number line"],            30),
    ("Practical Geometry",              ["construction", "compass", "triangles"],            25),
    ("Perimeter and Area",              ["perimeter", "area", "circle", "rectangle"],        30),
    ("Algebraic Expressions",           ["algebra", "terms", "expressions", "like terms"],  30),
    ("Exponents and Powers",            ["exponents", "powers", "standard form"],            25),
    ("Symmetry",                        ["symmetry", "reflection", "rotational"],            20),
    ("Visualising Solid Shapes",        ["3D shapes", "nets", "cross-sections"],             25),
]  # book code: gemh1

SCI_7 = [
    ("Nutrition in Plants",                             ["photosynthesis", "nutrients", "plants"],       30),
    ("Nutrition in Animals",                            ["digestion", "stomach", "nutrients"],           30),
    ("Fibre to Fabric",                                 ["wool", "silk", "fibre", "rearing"],            20),
    ("Heat",                                            ["temperature", "thermometer", "conduction"],    25),
    ("Acids, Bases and Salts",                          ["acid", "base", "salt", "indicator"],           30),
    ("Physical and Chemical Changes",                   ["physical change", "chemical change"],          25),
    ("Weather, Climate and Adaptations of Animals to Climate", ["weather", "climate", "adaptation"],    25),
    ("Winds, Storms and Cyclones",                      ["wind", "cyclone", "storm", "pressure"],        25),
    ("Soil",                                            ["soil", "humus", "layers", "erosion"],          20),
    ("Respiration in Organisms",                        ["respiration", "lungs", "anaerobic"],           25),
    ("Transportation in Animals and Plants",            ["blood", "heart", "xylem", "phloem"],           30),
    ("Reproduction in Plants",                          ["reproduction", "pollination", "seed"],         25),
    ("Motion and Time",                                 ["speed", "time", "distance", "graph"],          25),
    ("Electric Current and Its Effects",                ["electric current", "circuit", "electromagnet"],30),
    ("Light",                                           ["reflection", "mirrors", "lenses", "light"],    30),
    ("Water: A Precious Resource",                      ["water", "water table", "conservation"],        20),
    ("Forests: Our Lifeline",                           ["forest", "ecosystem", "biodiversity"],         20),
    ("Wastewater Story",                                ["sewage", "treatment", "water", "sanitation"],  20),
]  # book code: gesc1

SOCIAL_7 = [
    # History — Our Pasts II (ch 1–10)
    ("Tracing Changes through a Thousand Years",        ["medieval", "history", "sources"],              25),
    ("New Kings and Kingdoms",                          ["kingdoms", "Rashtrakutas", "Cholas"],          25),
    ("The Delhi Sultans",                               ["Delhi Sultanate", "Iltutmish", "rulers"],      30),
    ("The Mughal Empire",                               ["Mughals", "Akbar", "empire"],                  30),
    ("Rulers and Buildings",                            ["architecture", "mosques", "temples"],          25),
    ("Towns, Traders and Craftspersons",                ["trade", "towns", "crafts", "guilds"],          25),
    ("Tribes, Nomads and Settled Communities",          ["tribes", "nomads", "Gonds", "Ahoms"],          25),
    ("Devotional Paths to the Divine",                  ["bhakti", "Kabir", "Mirabai", "Sufism"],        25),
    ("The Making of Regional Cultures",                 ["regional", "culture", "language", "art"],      25),
    ("Eighteenth-Century Political Formations",         ["Marathas", "Sikhs", "regional powers"],        25),
    # Geography — Our Environment (ch 11–19)
    ("Environment",                                     ["environment", "biotic", "abiotic"],            20),
    ("Inside Our Earth",                                ["crust", "mantle", "core", "rocks"],            25),
    ("Our Changing Earth",                              ["earthquakes", "volcanoes", "erosion"],         25),
    ("Air",                                             ["atmosphere", "layers", "weather"],             25),
    ("Water",                                           ["ocean", "rivers", "water cycle"],              25),
    ("Natural Vegetation and Wildlife",                 ["vegetation", "biodiversity", "biomes"],        25),
    ("Human Environments — Settlements, Transport and Communication", ["settlements", "transport"],      25),
    ("Human–Environment Interactions: The Tropical and the Subtropical Region", ["tropical", "amazon"],  25),
    ("Life in the Temperate Grasslands",                ["grasslands", "prairies", "steppes"],           20),
    # Civics — Social and Political Life II (ch 20–28)
    ("On Equality",                                     ["equality", "rights", "discrimination"],        20),
    ("Role of the Government in Health",                ["government", "health", "public services"],     20),
    ("How the State Government Works",                  ["state government", "MLA", "legislature"],      25),
    ("Growing Up as Boys and Girls",                    ["gender", "roles", "equality"],                 20),
    ("Women Change the World",                          ["women", "education", "empowerment"],           20),
    ("Understanding Media",                             ["media", "television", "newspapers"],           20),
    ("Understanding Advertising",                       ["advertising", "media", "consumer"],            15),
    ("Markets Around Us",                               ["market", "trade", "retail", "wholesale"],      20),
    ("A Shirt in the Market",                           ["production", "trade chain", "workers"],        20),
]

ENGLISH_7 = [
    ("Three Questions",                     ["story", "Tolstoy", "wisdom", "king"],       20),
    ("A Gift of Chappals",                  ["story", "India", "generosity"],             20),
    ("Gopal and the Hilsa Fish",            ["story", "wit", "humour", "market"],         20),
    ("The Ashes That Made Trees Bloom",     ["story", "Japan", "gardener", "kindness"],   20),
    ("Quality",                             ["story", "craftsmanship", "bootmaker"],      20),
    ("Expert Detectives",                   ["story", "mystery", "children"],             20),
    ("The Invention of Vita-Wonk",          ["story", "Roald Dahl", "invention"],         20),
    ("Fire: Friend and Foe",                ["essay", "fire", "uses", "dangers"],         15),
    ("A Bicycle in Good Repair",            ["humour", "Jerome K. Jerome"],               20),
    ("The Story of Cricket",                ["history", "cricket", "sport"],              25),
]

HINDI_7 = [
    ("Hum Panchi Unmukt Gagan Ke",         ["poetry", "freedom", "birds"],              15),
    ("Dadi Maa",                            ["story", "grandmother", "values"],          20),
    ("Himalaya Ki Betiyan",                 ["essay", "Himalaya", "rivers"],             20),
    ("Kathaputli",                          ["poetry", "puppets", "freedom"],            15),
    ("Mithai Wala",                         ["story", "toy seller", "happiness"],        20),
    ("Rakt aur Hamara Sharir",              ["essay", "blood", "body", "science"],       20),
    ("Papa Kho Gaye",                        ["play", "drama", "child", "lost"],          20),
    ("Sham — Ek Kisaan",                    ["poetry", "farmer", "evening"],             15),
    ("Chidiya Ki Bachchi",                  ["story", "bird", "freedom", "cage"],        20),
    ("Apoorv Anubhav",                      ["autobiography", "mountaineer", "courage"], 20),
    ("Rahi Masum Raza — Ek Lesson",         ["story", "education", "teacher"],           20),
    ("Kancha",                              ["story", "childhood", "marbles"],           20),
    ("Ek Tinaka",                           ["poetry", "humility", "lesson"],            15),
    ("Khanabadosh",                         ["story", "nomads", "life"],                 20),
    ("Neem",                                ["essay", "neem tree", "nature", "culture"], 20),
    ("Bhor aur Barkha (Mirabai)",           ["poetry", "devotion", "Krishna"],           15),
    ("Veer Kunwar Singh",                   ["biography", "1857", "rebellion"],          20),
]

# ─── Class 8 ────────────────────────────────────────────────────────────────

MATH_8 = [
    ("Rational Numbers",                        ["rational numbers", "properties", "number line"], 30),
    ("Linear Equations in One Variable",        ["algebra", "equations", "linear"],                35),
    ("Understanding Quadrilaterals",            ["quadrilaterals", "polygons", "angles"],           30),
    ("Practical Geometry",                      ["construction", "quadrilaterals", "compass"],      25),
    ("Data Handling",                           ["data", "pie chart", "histogram", "probability"],  30),
    ("Squares and Square Roots",                ["squares", "square roots", "patterns"],            35),
    ("Cubes and Cube Roots",                    ["cubes", "cube roots", "perfect cubes"],           30),
    ("Comparing Quantities",                    ["percentage", "profit", "loss", "interest"],       35),
    ("Algebraic Expressions and Identities",    ["algebra", "identities", "expansion"],             35),
    ("Visualising Solid Shapes",                ["3D shapes", "nets", "Euler's formula"],           25),
    ("Mensuration",                             ["area", "volume", "surface area", "cylinder"],     40),
    ("Exponents and Powers",                    ["exponents", "powers", "standard form"],           25),
    ("Direct and Inverse Proportions",          ["proportion", "direct", "inverse"],                30),
    ("Factorisation",                           ["factorisation", "division", "algebra"],           35),
    ("Introduction to Graphs",                  ["graphs", "coordinate plane", "linear"],           25),
    ("Playing with Numbers",                    ["numbers", "divisibility", "puzzles"],             20),
]  # book code: hemh1

SCI_8 = [
    ("Crop Production and Management",          ["agriculture", "crops", "irrigation", "farming"],  30),
    ("Microorganisms: Friend and Foe",          ["microorganisms", "bacteria", "fungi", "disease"], 30),
    ("Synthetic Fibres and Plastics",           ["fibre", "plastic", "synthetic", "polymer"],       25),
    ("Materials: Metals and Non-Metals",        ["metals", "non-metals", "properties", "reactions"],30),
    ("Coal and Petroleum",                      ["fossil fuels", "coal", "petroleum", "energy"],    25),
    ("Combustion and Flame",                    ["combustion", "fire", "fuel", "flame"],            25),
    ("Conservation of Plants and Animals",       ["wildlife", "deforestation", "conservation"],     25),
    ("Cell — Structure and Functions",           ["cell", "nucleus", "organelles", "biology"],      30),
    ("Reproduction in Animals",                 ["reproduction", "sexual", "asexual", "embryo"],   30),
    ("Reaching the Age of Adolescence",         ["adolescence", "hormones", "puberty"],             25),
    ("Force and Pressure",                      ["force", "pressure", "balanced forces"],           30),
    ("Friction",                                ["friction", "types", "motion"],                    25),
    ("Sound",                                   ["sound", "waves", "pitch", "frequency"],           30),
    ("Chemical Effects of Electric Current",    ["electrolysis", "current", "chemical change"],     25),
    ("Some Natural Phenomena",                  ["lightning", "earthquakes", "static electricity"], 25),
    ("Light",                                   ["reflection", "mirrors", "kaleidoscope", "light"], 30),
    ("Stars and the Solar System",              ["stars", "planets", "solar system", "moon"],       30),
    ("Pollution of Air and Water",              ["pollution", "air quality", "water treatment"],    25),
]  # book code: hesc1

SOCIAL_8 = [
    # History — Our Pasts III (ch 1–10)
    ("How, When and Where",                         ["history", "sources", "colonial", "dates"],       20),
    ("From Trade to Territory: The Company Establishes Power", ["East India Company", "trade"],        30),
    ("Ruling the Countryside",                      ["agriculture", "ryots", "colonial revenue"],      25),
    ("Tribals, Dikus and the Vision of a Golden Age", ["tribes", "forest", "Birsa Munda"],             25),
    ("When People Rebel: 1857 and After",           ["1857", "sepoy mutiny", "revolt"],                30),
    ("Weavers, Iron Smelters and Factory Owners",   ["industry", "weavers", "craftspeople"],           25),
    ("Civilising the 'Native', Educating the Nation", ["education", "colonial", "missionaries"],       25),
    ("Women, Caste and Reform",                     ["women", "caste", "social reform", "Sati"],       25),
    ("The Making of the National Movement: 1870s–1947", ["nationalism", "Gandhi", "Congress"],         30),
    ("India After Independence",                    ["partition", "constitution", "democracy"],        25),
    # Geography — Resources and Development (ch 11–16)
    ("Resources",                                   ["resources", "natural", "human", "types"],        20),
    ("Land, Soil, Water, Natural Vegetation and Wildlife Resources", ["land use", "soil", "forests"],   25),
    ("Mineral and Power Resources",                 ["minerals", "energy", "coal", "petroleum"],       25),
    ("Agriculture",                                 ["farming", "types of agriculture", "crops"],      25),
    ("Industries",                                  ["industry", "manufacturing", "cotton", "steel"],  25),
    ("Human Resources",                             ["population", "human resources", "development"],  20),
    # Civics — Social and Political Life III (ch 17–26)
    ("The Indian Constitution",                     ["constitution", "rights", "amendment"],           25),
    ("Understanding Secularism",                    ["secularism", "religion", "state"],               20),
    ("Why Do We Need a Parliament?",                ["parliament", "democracy", "representation"],     20),
    ("Understanding Laws",                          ["laws", "legislature", "rights"],                 20),
    ("Judiciary",                                   ["judiciary", "courts", "justice"],                20),
    ("Understanding Our Criminal Justice System",   ["FIR", "police", "courts", "rights"],             20),
    ("Understanding Marginalisation",               ["marginalisation", "Adivasi", "Dalit"],           20),
    ("Confronting Marginalisation",                 ["rights", "reservation", "struggle"],             20),
    ("Public Facilities",                           ["public services", "water", "health"],            20),
    ("Law and Social Justice",                      ["laws", "workers", "minimum wage"],               20),
]

ENGLISH_8 = [
    # Honeydew (ch 1–10)
    ("The Best Christmas Present in the World",     ["war", "Christmas", "letter", "humanity"],      25),
    ("The Tsunami",                                  ["disaster", "tsunami", "animals", "survival"],  25),
    ("Glimpses of the Past",                        ["history", "comics", "colonial India"],          25),
    ("Bepin Choudhury's Lapse of Memory",           ["humour", "memory", "identity"],                25),
    ("The Summit Within",                           ["mountaineering", "Everest", "achievement"],     20),
    ("This is Jody's Fawn",                         ["story", "deer", "friendship", "responsibility"],20),
    ("A Visit to Cambridge",                        ["biography", "Stephen Hawking", "disability"],   20),
    ("A Short Monsoon Diary",                       ["diary", "nature", "seasons"],                   20),
    ("The Great Stone Face — I",                    ["allegory", "virtue", "Hawthorne"],              25),
    ("The Great Stone Face — II",                   ["allegory", "virtue", "destiny"],                25),
    # It So Happened supplementary (ch 11–12 mapped to first 2)
    ("How the Camel Got His Hump",                  ["fable", "Kipling", "camel", "work"],           20),
    ("Children at Work",                            ["child labour", "poverty", "society"],           20),
]  # book code: cesa1

HINDI_8 = [
    ("Dhuni Ki Dhunki (Phanishvar Nath Renu)",       ["story", "village", "craftsmanship"],     20),
    ("Lakh Ki Chudiyan (Kamtanath)",                  ["story", "tradition", "plastic vs craft"],20),
    ("Bus Ki Yatra (Harishankar Parsai)",             ["humour", "satire", "bus", "journey"],    20),
    ("Deewano Ki Hasti (Bhagwati Charan Verma)",      ["poetry", "wanderers", "freedom"],        15),
    ("Chitthiyon Ki Anoothi Duniya",                  ["essay", "letters", "communication"],     20),
    ("Bhagawan Ke Dake (Rambriksh Benipuri)",         ["essay", "nature", "seasons"],             20),
    ("Kya Nirash Hua Jaye (Hazari Prasad Dvivedi)",   ["essay", "hope", "values"],               20),
    ("Yah Sabse Kathin Samay Nahi (Jaya Jadvani)",    ["poetry", "hope", "courage"],             15),
    ("Kabir Ki Sakhi",                                ["poetry", "Kabir", "dohas", "wisdom"],    15),
    ("Kamchor (Ismat Chughtai)",                      ["humour", "laziness", "children"],        20),
    ("Jab Cinema Ne Bolna Sikhha",                    ["essay", "cinema", "history", "talkie"],   20),
    ("Sudama Charit (Narrottamdas)",                  ["poetry", "friendship", "Krishna", "Sudama"],15),
    ("Jaha Pahiya Hai (P. Sainath)",                  ["essay", "cycling", "women", "Tamil Nadu"],20),
    ("Akbari Lota (Annapurnanand Verma)",              ["humour", "pot", "Akbar", "trick"],       20),
    ("Surdas Ke Pad (Surdas)",                        ["poetry", "devotion", "Krishna", "Surdas"],15),
    ("Paani Ki Kahani (Ramchandra Tiwari)",            ["essay", "water", "cycle", "science"],    20),
    ("Baj Raha Hai Nagara (Ramdhari Singh Dinkar)",   ["poetry", "patriotism", "courage"],       15),
]

# ─── Class 9 ────────────────────────────────────────────────────────────────
# (already seeded but extended here so all grades are consistent/complete)

MATH_9 = [
    ("Number Systems",                          ["numbers", "irrational", "real numbers"],             30),
    ("Polynomials",                             ["polynomials", "factorisation", "remainder theorem"], 35),
    ("Coordinate Geometry",                     ["coordinates", "Cartesian plane", "axes"],            25),
    ("Linear Equations in Two Variables",       ["linear equations", "graphing", "solutions"],         30),
    ("Introduction to Euclid's Geometry",       ["Euclid", "axioms", "postulates", "geometry"],        25),
    ("Lines and Angles",                        ["lines", "angles", "parallel lines", "transversal"],  30),
    ("Triangles",                               ["triangles", "congruence", "theorems"],               40),
    ("Quadrilaterals",                          ["quadrilaterals", "parallelogram", "properties"],     30),
    ("Circles",                                 ["circles", "chords", "arcs", "angles"],               35),
    ("Heron's Formula",                         ["mensuration", "area", "heron", "triangle"],          20),
    ("Surface Areas and Volumes",               ["surface area", "volume", "cone", "sphere"],          45),
    ("Statistics",                              ["statistics", "mean", "median", "mode"],              35),
    ("Probability",                             ["probability", "events", "experiments"],              30),
    ("Areas of Parallelograms and Triangles",   ["areas", "parallelogram", "triangle", "proof"],       25),
    ("Constructions",                           ["construction", "compass", "bisect", "triangles"],    20),
]  # book code: iemh1

SCI_9 = [
    ("Matter in Our Surroundings",              ["matter", "states", "solidification", "evaporation"], 30),
    ("Is Matter Around Us Pure?",               ["mixture", "solution", "colloid", "suspension"],      35),
    ("Atoms and Molecules",                     ["atoms", "molecules", "mole", "formulae"],            40),
    ("Structure of the Atom",                   ["atomic structure", "electrons", "protons", "Bohr"],  35),
    ("The Fundamental Unit of Life",            ["cell", "organelles", "prokaryote", "eukaryote"],     35),
    ("Tissues",                                 ["tissues", "plant tissue", "animal tissue"],          30),
    ("Motion",                                  ["motion", "velocity", "acceleration", "distance"],    40),
    ("Force and Laws of Motion",                ["force", "Newton's laws", "momentum", "friction"],    40),
    ("Gravitation",                             ["gravity", "gravitation", "weight", "pressure"],      35),
    ("Work and Energy",                         ["work", "energy", "power", "kinetic", "potential"],   35),
    ("Sound",                                   ["sound", "waves", "echo", "ultrasound"],              30),
    ("Improvement in Food Resources",           ["agriculture", "crops", "animal husbandry"],          25),
    ("Why Do We Fall Ill?",                     ["health", "disease", "immunity", "infection"],        25),
    ("Natural Resources",                       ["resources", "air", "water", "biogeochemical"],       25),
    ("Improvement in Food Resources (Ext)",     ["food production", "fertiliser", "pest control"],     25),
]  # book code: iesc1

SOCIAL_9 = [
    # History — India and the Contemporary World I
    ("The French Revolution",                           ["French Revolution", "liberty", "equality"],  35),
    ("Socialism in Europe and the Russian Revolution",  ["socialism", "Russian Revolution", "Lenin"],  35),
    ("Nazism and the Rise of Hitler",                   ["Nazism", "Hitler", "Holocaust", "World War"],35),
    ("Forest Society and Colonialism",                  ["forests", "colonial", "tribal", "ecology"],  30),
    ("Pastoralists in the Modern World",                ["pastoralists", "nomads", "trade"],           30),
    # Geography — Contemporary India I
    ("India — Size and Location",                       ["India", "latitude", "longitude", "location"],25),
    ("Physical Features of India",                      ["Himalaya", "plains", "plateau", "India"],    30),
    ("Drainage",                                        ["rivers", "drainage", "watersheds", "India"], 30),
    ("Climate",                                         ["climate", "monsoon", "India", "seasons"],    30),
    ("Natural Vegetation and Wildlife",                 ["vegetation", "biomes", "wildlife", "India"], 25),
    ("Population",                                      ["population", "census", "India", "density"],  25),
    # Civics — Democratic Politics I
    ("What is Democracy? Why Democracy?",               ["democracy", "rights", "government"],         25),
    ("Constitutional Design",                           ["constitution", "rights", "South Africa"],    30),
    ("Electoral Politics",                              ["elections", "voters", "process"],            25),
    ("Working of Institutions",                         ["parliament", "judiciary", "executive"],      30),
    ("Democratic Rights",                               ["rights", "constitution", "India"],           25),
    # Economics
    ("The Story of Village Palampur",                   ["village", "farming", "production"],          25),
    ("People as Resource",                              ["human capital", "education", "health"],      25),
    ("Poverty as a Challenge",                          ["poverty", "indicators", "India"],             30),
    ("Food Security in India",                          ["food security", "buffer stock", "PDS"],       25),
]

ENGLISH_9 = [
    # Beehive
    ("The Fun They Had",                        ["Asimov", "future", "school", "robot"],          20),
    ("The Sound of Music",                      ["biography", "music", "disability"],             20),
    ("The Little Girl",                         ["story", "parent-child", "fear"],                20),
    ("A Truly Beautiful Mind",                  ["biography", "Einstein", "peace"],               20),
    ("The Snake and the Mirror",                ["humour", "vanity", "doctor", "snake"],          20),
    ("My Childhood",                            ["autobiography", "APJ Abdul Kalam"],             20),
    ("Packing",                                 ["humour", "Jerome K. Jerome", "packing"],        20),
    ("Reach for the Top",                       ["biography", "mountaineer", "ambition"],         20),
    ("The Bond of Love",                        ["story", "bear", "love", "freedom"],             20),
    ("Kathmandu",                               ["travel", "Nepal", "Kathmandu", "Vikram Seth"],  20),
    ("If I Were You",                           ["play", "drama", "suspense"],                    20),
    # Moments — supplementary
    ("The Lost Child",                          ["story", "festival", "child", "separation"],     20),
    ("The Adventures of Toto",                  ["humour", "monkey", "Ruskin Bond"],              20),
    ("Iswaran the Storyteller",                 ["story", "ghost", "cook", "narration"],          20),
    ("In the Kingdom of Fools",                 ["fable", "foolishness", "justice"],              20),
    ("Happy Prince",                            ["Oscar Wilde", "compassion", "sacrifice"],       20),
    ("Weathering the Storm in Erasma",          ["cyclone", "Orissa", "survival"],                20),
    ("The Last Leaf",                           ["O. Henry", "friendship", "art", "hope"],        20),
    ("A House is Not a Home",                   ["story", "loss", "cat", "school"],               20),
    ("The Accidental Tourist",                  ["humour", "Bill Bryson", "travel"],              20),
    ("The Beggar",                              ["Chekhov", "redemption", "poverty"],             20),
]

HINDI_9 = [
    ("Do Bailon Ki Katha (Premchand)",            ["story", "oxen", "freedom", "friendship"],     20),
    ("Lhasa Ki Or (Rahul Sankrityayan)",           ["travel", "Tibet", "autobiography"],           20),
    ("Upbhoktavad Ki Sanskriti (Shyam Charan Dubey)", ["essay", "consumerism", "culture"],        20),
    ("Sawale Sapanon Ki Yaad",                    ["biography", "Salim Ali", "birds"],            20),
    ("Nana Sahab Ki Putri Devbala (Chapala Devi)", ["story", "1857", "history", "bravery"],        20),
    ("Premchand Ke Phate Joote (Harishankar Parsai)", ["essay", "satire", "Premchand"],           20),
    ("Mere Bachpan Ke Din (Mahadevi Verma)",       ["autobiography", "childhood", "memories"],    20),
    ("Ek Kutta aur Ek Maina (Hazari Prasad Dvivedi)", ["essay", "animals", "philosophy"],         20),
    ("Sakhiyan Evam Sabad (Kabir)",               ["poetry", "dohas", "devotion", "wisdom"],     15),
    ("Vakh (Lalded)",                              ["poetry", "Kashmir", "mysticism"],             15),
    ("Savaiye (Raskhan)",                          ["poetry", "devotion", "Krishna"],              15),
    ("Kaidi aur Kokila (Madhav Prasad Mishra)",    ["poetry", "freedom", "bird", "colonialism"],  15),
    ("Gram Sri (Sumitraanandan Pant)",             ["poetry", "village", "nature"],               15),
    ("Chandra Gahna Se Laurti Ber (Kedarnath Singh)", ["poetry", "nature", "rural life"],         15),
    ("Megh Aaye (Sarveshwar Dayal Saxena)",        ["poetry", "rain", "village"],                 15),
    ("Yamraj Ki Disha (Chandra Kant Devtale)",     ["poetry", "society", "direction"],            15),
    ("Bache Kaam Par Ja Rahe Hain (Rajesh Joshi)", ["poetry", "child labour", "society"],        15),
]  # book code: hhvn1

# ─── Class 10 ───────────────────────────────────────────────────────────────

MATH_10 = [
    ("Real Numbers",                                ["real numbers", "Euclid", "irrational"],         30),
    ("Polynomials",                                 ["polynomials", "zeroes", "division algorithm"],   30),
    ("Pair of Linear Equations in Two Variables",   ["linear equations", "graphical", "substitution"], 40),
    ("Quadratic Equations",                         ["quadratic", "factorisation", "discriminant"],    35),
    ("Arithmetic Progressions",                     ["AP", "common difference", "nth term", "sum"],    35),
    ("Triangles",                                   ["similar triangles", "Pythagoras", "BPT"],        40),
    ("Coordinate Geometry",                         ["distance", "section formula", "mid-point"],      30),
    ("Introduction to Trigonometry",                ["sin", "cos", "tan", "ratios"],                   35),
    ("Some Applications of Trigonometry",           ["heights", "distances", "angle of elevation"],    30),
    ("Circles",                                     ["tangent", "circle", "secant", "chord"],          30),
    ("Constructions",                               ["construction", "tangent", "similar triangles"],  25),
    ("Areas Related to Circles",                    ["area", "sector", "segment", "circle"],           30),
    ("Surface Areas and Volumes",                   ["surface area", "volume", "combination of solids"],40),
    ("Statistics",                                  ["mean", "median", "mode", "ogive"],               35),
    ("Probability",                                 ["probability", "events", "classical"],            30),
]  # book code: jemh1

SCI_10 = [
    ("Chemical Reactions and Equations",            ["chemical reactions", "balancing", "types"],       35),
    ("Acids, Bases and Salts",                      ["acid", "base", "salt", "pH", "neutralisation"],  35),
    ("Metals and Non-metals",                       ["metals", "non-metals", "reactivity", "alloys"],  35),
    ("Carbon and Its Compounds",                    ["carbon", "organic", "covalent", "hydrocarbons"], 40),
    ("Periodic Classification of Elements",         ["periodic table", "Mendeleev", "periods", "groups"],30),
    ("Life Processes",                              ["nutrition", "respiration", "circulation", "excretion"],40),
    ("Control and Coordination",                    ["nervous system", "hormones", "reflex"],          35),
    ("How do Organisms Reproduce?",                 ["reproduction", "sexual", "asexual", "DNA"],      35),
    ("Heredity and Evolution",                      ["heredity", "Mendel", "natural selection"],       35),
    ("Light – Reflection and Refraction",           ["reflection", "refraction", "mirrors", "lenses"], 40),
    ("The Human Eye and the Colourful World",       ["eye", "defects", "dispersion", "rainbow"],       30),
    ("Electricity",                                 ["current", "resistance", "Ohm's law", "circuits"],40),
    ("Magnetic Effects of Electric Current",        ["electromagnetism", "motor", "generator"],        35),
    ("Sources of Energy",                           ["fossil fuels", "solar", "nuclear", "renewable"], 30),
    ("Our Environment",                             ["ecosystem", "food chain", "pollution", "ozone"], 25),
    ("Management of Natural Resources",             ["conservation", "forests", "water", "coal"],      25),
]  # book code: jesc1

SOCIAL_10 = [
    # History — India and the Contemporary World II (ch 1–5)
    ("The Rise of Nationalism in Europe",           ["nationalism", "Europe", "French Revolution"],   35),
    ("Nationalism in India",                        ["Gandhi", "non-cooperation", "Independence"],    40),
    ("The Making of a Global World",                ["globalisation", "trade", "history"],            35),
    ("The Age of Industrialisation",                ["industrialisation", "factories", "textiles"],   30),
    ("Print Culture and the Modern World",          ["printing press", "books", "newspapers"],        30),
    # Geography — Contemporary India II (ch 6–12)
    ("Resources and Development",                   ["resources", "land use", "soil", "conservation"],25),
    ("Forest and Wildlife Resources",               ["forests", "wildlife", "conservation", "India"], 25),
    ("Water Resources",                             ["dams", "rainwater harvesting", "water"],        25),
    ("Agriculture",                                 ["agriculture", "crops", "cropping patterns"],    30),
    ("Minerals and Energy Resources",               ["minerals", "energy", "coal", "petroleum"],      25),
    ("Manufacturing Industries",                    ["industries", "textile", "steel", "IT"],         30),
    ("Lifelines of National Economy",               ["transport", "trade", "roads", "railways"],      25),
    # Civics — Democratic Politics II (ch 13–20)
    ("Power Sharing",                               ["power sharing", "Belgium", "democracy"],        25),
    ("Federalism",                                  ["federalism", "states", "India", "Belgium"],     30),
    ("Democracy and Diversity",                     ["diversity", "social divisions", "politics"],    25),
    ("Gender, Religion and Caste",                  ["gender", "caste", "religion", "politics"],      25),
    ("Popular Struggles and Movements",             ["movements", "Bolivia", "Nepal", "democracy"],   25),
    ("Political Parties",                           ["parties", "election", "multiparty"],            25),
    ("Outcomes of Democracy",                       ["democratic outcomes", "equality", "growth"],    25),
    ("Challenges to Democracy",                     ["challenges", "participation", "corruption"],    25),
    # Economics (ch 21–25)
    ("Development",                                 ["development", "GDP", "income", "progress"],     25),
    ("Sectors of the Indian Economy",               ["primary", "secondary", "tertiary", "India"],    25),
    ("Money and Credit",                            ["money", "credit", "banking", "loan"],           25),
    ("Globalisation and the Indian Economy",        ["globalisation", "MNC", "trade", "India"],       25),
    ("Consumer Rights",                             ["consumer", "rights", "ISI", "COPRA"],           20),
]

ENGLISH_10 = [
    # First Flight (jfls1) ch 1–11
    ("A Letter to God",                             ["faith", "nature", "letter", "God"],             20),
    ("Nelson Mandela: Long Walk to Freedom",        ["biography", "Mandela", "apartheid"],            25),
    ("Two Stories about Flying",                    ["courage", "determination", "bird"],             20),
    ("From the Diary of Anne Frank",                ["diary", "Holocaust", "Anne Frank"],             25),
    ("The Hundred Dresses — I",                     ["story", "bullying", "class", "Poland"],         20),
    ("The Hundred Dresses — II",                    ["story", "regret", "kindness", "Poland"],        20),
    ("Glimpses of India",                           ["travel", "Coorg", "tea", "bakery"],             25),
    ("Mijbil the Otter",                            ["story", "otter", "Gavin Maxwell", "pet"],       20),
    ("Madam Rides the Bus",                         ["story", "Valli", "bus", "Tamil Nadu"],          20),
    ("The Sermon at Benares",                       ["Buddha", "wisdom", "grief", "death"],           20),
    ("The Proposal",                                ["Chekhov", "play", "comedy", "proposal"],        25),
    # Footprints without Feet (jfcs1) ch 12–21
    ("A Triumph of Surgery",                        ["story", "dog", "vet", "Herriot"],               20),
    ("The Thief's Story",                           ["story", "trust", "Ruskin Bond"],                20),
    ("The Midnight Visitor",                        ["spy", "mystery", "hotel", "Fowler"],            20),
    ("A Question of Trust",                         ["story", "thief", "old lady"],                   20),
    ("Footprints without Feet",                     ["H.G. Wells", "invisible man", "footprints"],    20),
    ("The Making of a Scientist",                   ["biography", "science", "Ebright", "butterfly"], 25),
    ("The Necklace",                                ["Guy de Maupassant", "pride", "irony"],          25),
    ("The Hack Driver",                             ["Sinclair Lewis", "humour", "detective"],        20),
    ("Bholi",                                       ["story", "girl", "confidence", "education"],     20),
    ("The Book That Saved the Earth",               ["science fiction", "aliens", "library"],         20),
]  # codes: jfls1 (ch 1–11), jfcs1 (ch 12–21, offset 11)

HINDI_10 = [
    ("Pad (Surdas)",                                ["poetry", "devotion", "Krishna", "Surdas"],       15),
    ("Ram-Lakshman-Parshuram Samvad (Tulsidas)",    ["poetry", "Ramayana", "dialogue"],                15),
    ("Savaiya aur Kavitt (Dev)",                    ["poetry", "Riti Kaal", "nature", "devotion"],     15),
    ("Aatmakatha (Jaishankar Prasad)",              ["poetry", "autobiography", "inner self"],         15),
    ("Utsah aur Ant Ka Nirash (Nirala)",            ["poetry", "inspiration", "nature"],               15),
    ("Yah Danturit Muskan (Nagarjun)",              ["poetry", "children", "smile", "innocence"],      15),
    ("Chaya Mat Chhuona (Girija Kumar Mathur)",     ["poetry", "time", "nostalgia", "past"],           15),
    ("Kanyadan (Rituraj)",                          ["poetry", "daughter", "society", "marriage"],     15),
    ("Sangatkar (Manglesh Dabral)",                 ["poetry", "music", "background singer"],          15),
    ("Netaji Ka Chashma (Swaym Prakash)",           ["story", "patriotism", "statue", "spectacles"],  20),
    ("Balyotsav (Ramshankar Shukla Rasaal)",        ["story", "childhood", "festival"],               20),
    ("Lakhnavi Andaaz (Yashpal)",                   ["story", "nawab", "culture", "satire"],          20),
    ("Manviya Karuna Ki Divya Chamak (S.D. Saxena)", ["essay", "Father Kamil Bulcke", "humanity"],      20),
    ("Ek Kahani Yeh Bhi (Mannu Bhandari)",          ["autobiography", "feminism", "writing"],         20),
    ("Stree Shiksha Ke Virodhi Kutarkon Ka Khandan", ["essay", "women's education", "Mahadevi Verma"],  20),
    ("Naubatkhane Mein Ibadat (Yatindra Misra)",    ["essay", "music", "Bismillah Khan"],             20),
    ("Sanskriti (Bhagwansharan Upadhyay)",          ["essay", "culture", "civilization"],             20),
]  # book code: khvn1


# ══════════════════════════════════════════════════════════════════════════════
# MASTER TABLE
# Maps grade_label → subject_id → {meta, chapters, book_code}
# ══════════════════════════════════════════════════════════════════════════════

def make_subject(subject_id, name, grade, description, display_order):
    return {
        "id": subject_id,
        "subject_id": subject_id,
        "name": name,
        "grade": grade,
        "description": description,
        "display_order": display_order,
        "icon_url": "",
    }


GRADE_SUBJECTS = [
    # ── Class 6 ──────────────────────────────────────────────────────────────
    ("math_6th",    "Mathematics",   "6th", "NCERT Class 6 Mathematics",                        1, MATH_6,    "femh1"),
    ("science_6th", "Science",       "6th", "NCERT Class 6 Science",                            2, SCI_6,     "fesc1"),
    ("social_6th",  "Social Science","6th", "NCERT Class 6 Social Science (History+Geo+Civics)",3, SOCIAL_6,  ""),
    ("english_6th", "English",       "6th", "NCERT Class 6 English (Honeysuckle)",              4, ENGLISH_6, ""),
    ("hindi_6th",   "Hindi",         "6th", "NCERT Class 6 Hindi (Vasant I)",                   5, HINDI_6,   ""),
    # ── Class 7 ──────────────────────────────────────────────────────────────
    ("math_7th",    "Mathematics",   "7th", "NCERT Class 7 Mathematics",                        1, MATH_7,    "gemh1"),
    ("science_7th", "Science",       "7th", "NCERT Class 7 Science",                            2, SCI_7,     "gesc1"),
    ("social_7th",  "Social Science","7th", "NCERT Class 7 Social Science (History+Geo+Civics)",3, SOCIAL_7,  ""),
    ("english_7th", "English",       "7th", "NCERT Class 7 English (Honeycomb)",                4, ENGLISH_7, ""),
    ("hindi_7th",   "Hindi",         "7th", "NCERT Class 7 Hindi (Vasant II)",                  5, HINDI_7,   ""),
    # ── Class 8 ──────────────────────────────────────────────────────────────
    ("math_8th",    "Mathematics",   "8th", "NCERT Class 8 Mathematics",                        1, MATH_8,    "hemh1"),
    ("science_8th", "Science",       "8th", "NCERT Class 8 Science",                            2, SCI_8,     "hesc1"),
    ("social_8th",  "Social Science","8th", "NCERT Class 8 Social Science (History+Geo+Civics)",3, SOCIAL_8,  ""),
    ("english_8th", "English",       "8th", "NCERT Class 8 English (Honeydew)",                 4, ENGLISH_8, "cesa1"),
    ("hindi_8th",   "Hindi",         "8th", "NCERT Class 8 Hindi (Vasant III)",                 5, HINDI_8,   ""),
    # ── Class 9 ──────────────────────────────────────────────────────────────
    ("math_9th",    "Mathematics",   "9th", "NCERT Class 9 Mathematics",                        1, MATH_9,    "iemh1"),
    ("science_9th", "Science",       "9th", "NCERT Class 9 Science",                            2, SCI_9,     "iesc1"),
    ("social_9th",  "Social Science","9th", "NCERT Class 9 Social Science (History+Geo+Civics)",3, SOCIAL_9,  ""),
    ("english_9th", "English",       "9th", "NCERT Class 9 English (Beehive + Moments)",        4, ENGLISH_9, ""),
    ("hindi_9th",   "Hindi",         "9th", "NCERT Class 9 Hindi (Kshitij I)",                  5, HINDI_9,   "hhvn1"),
    # ── Class 10 ─────────────────────────────────────────────────────────────
    ("math_10th",    "Mathematics",   "10th","NCERT Class 10 Mathematics",                       1, MATH_10,   "jemh1"),
    ("science_10th", "Science",       "10th","NCERT Class 10 Science",                           2, SCI_10,    "jesc1"),
    ("social_10th",  "Social Science","10th","NCERT Class 10 Social Science (History+Geo+Civics)",3,SOCIAL_10, ""),
    ("english_10th", "English",       "10th","NCERT Class 10 English (First Flight + Footprints)",4,ENGLISH_10,"jfls1"),
    ("hindi_10th",   "Hindi",         "10th","NCERT Class 10 Hindi (Kshitij II)",                5, HINDI_10,  "khvn1"),
]


def short_prefix(subject_id: str) -> str:
    """Returns a short 3-5 char prefix for chapter doc IDs, e.g. math9, sci10, soc8."""
    parts = subject_id.split("_")           # ["math", "9th"]
    subj = parts[0][:3]                     # "mat", "sci", "soc", "eng", "hin"
    grade = parts[1].replace("th", "")      # "9", "10", "6" …
    return f"{subj}{grade}"                 # "mat9", "sci10", "soc6" …


# ══════════════════════════════════════════════════════════════════════════════
# SEED
# ══════════════════════════════════════════════════════════════════════════════

MAX_BATCH = 400   # stay well under Firestore 500-op limit


def commit_batch(batch, ops):
    batch.commit()
    print(f"  → batch committed ({ops} ops)")
    return db.batch(), 0


def seed():
    total_subjects = 0
    total_chapters = 0

    batch = db.batch()
    ops = 0

    for (subject_id, name, grade, description, display_order, chapters_data, book_code) in GRADE_SUBJECTS:
        # ── Subject doc ──────────────────────────────────────────────────────
        subj_doc = {
            "subject_id": subject_id,
            "name": name,
            "grade": grade,
            "description": description,
            "display_order": display_order,
            "chapter_count": len(chapters_data),
            "icon_url": "",
        }
        batch.set(db.collection("subjects").document(subject_id), subj_doc)
        ops += 1
        total_subjects += 1

        if ops >= MAX_BATCH:
            batch, ops = commit_batch(batch, ops)

        # ── Chapter docs ─────────────────────────────────────────────────────
        prefix = short_prefix(subject_id)

        for order, (title, topic_tags, est_min) in enumerate(chapters_data, start=1):
            doc_id = f"{prefix}_ch{order}"

            # Build NCERT PDF URL
            ncert_url = ""
            if book_code:
                ncert_url = pdf(book_code, order)

            ch_doc = {
                "subject_id": subject_id,
                "title": title,
                "order": order,
                "topic_tags": topic_tags,
                "estimated_minutes": est_min,
                "ncert_pdf_url": ncert_url,
            }
            batch.set(db.collection("chapters").document(doc_id), ch_doc)
            ops += 1
            total_chapters += 1

            if ops >= MAX_BATCH:
                batch, ops = commit_batch(batch, ops)

        print(f"  queued: {subject_id} ({len(chapters_data)} chapters, code='{book_code or 'none'}')")

    # Commit remaining
    if ops > 0:
        commit_batch(batch, ops)

    print(f"\n✅ Done — {total_subjects} subjects, {total_chapters} chapters written to Firestore.")


if __name__ == "__main__":
    print("Seeding Firestore: grades 6–10, all subjects + chapters …\n")
    seed()
