"""
seed_onboarding_samples.py — Seed 10 sample BB lessons to Firestore bb_samples collection.

These lessons are copied to every new user's saved_bb_sessions_flat subcollection
on first registration (via user_service.copy_samples_to_user).

Run from the server/ directory:
    source myenv/bin/activate
    python seed_onboarding_samples.py

The script builds SVG html for diagram frames using the server's diagram engine,
so samples display full animated visuals on first launch.
"""

from __future__ import annotations

import json
import os
import sys
import time

# Allow imports from app/
sys.path.insert(0, os.path.dirname(__file__))

from app.utils.svg_builder import build_animated_svg, build_from_diagram_type
from app.utils.js_engine import build_js_diagram_html, _invalidate_engine_cache

_invalidate_engine_cache()


def _build_svg(diagram_type: str, data: dict) -> str:
    """Try JS engine first, fall back to SMIL svg_builder."""
    from app.utils.js_engine import SUPPORTED_TYPES
    d_lower = diagram_type.lower()
    if d_lower in SUPPORTED_TYPES:
        html = build_js_diagram_html(d_lower, data)
        if html:
            return html
    shapes = build_from_diagram_type(diagram_type, data)
    if shapes:
        return build_animated_svg(shapes)
    return ""


def _frame(frame_type: str, text: str, speech: str, highlight=None,
           duration_ms=2500, diagram_type="", data=None,
           quiz_options=None, quiz_correct_index=-1,
           quiz_model_answer="", quiz_keywords=None) -> dict:
    svg_html = ""
    if frame_type == "diagram" and diagram_type:
        svg_html = _build_svg(diagram_type, data or {})
    return {
        "frame_type": frame_type,
        "text": text,
        "highlight": highlight or [],
        "speech": speech,
        "tts_engine": "gemini",
        "voice_role": "teacher",
        "duration_ms": duration_ms,
        "svg_html": svg_html,
        "quiz_answer": "",
        "quiz_options": quiz_options or [],
        "quiz_correct_index": quiz_correct_index,
        "quiz_model_answer": quiz_model_answer,
        "quiz_keywords": quiz_keywords or [],
        "fill_blanks": [],
        "quiz_correct_order": [],
    }


def _step(title: str, frames: list, lang="en-US") -> dict:
    return {
        "title": title,
        "lang": lang,
        "image_description": "",
        "imageConfidenceScore": 0.0,
        "frames": frames,
    }


# ── Sample 1: Pythagorean Theorem ─────────────────────────────────────────────
SAMPLE_PYTHAGOREAN = {
    "id": "sample_pythagorean",
    "title": "Pythagorean Theorem",
    "subject": "Mathematics",
    "chapter": "Geometry",
    "steps": json.dumps([
        _step("What is it?", [
            _frame("concept",
                text="**Pythagorean Theorem**\na² + b² = c²\nFor any right-angled triangle",
                speech="The Pythagorean theorem tells us that in a right triangle, the square of the hypotenuse equals the sum of squares of the other two sides.",
                highlight=["a² + b² = c²", "right-angled"]),
            _frame("diagram",
                text="Right triangle — sides a, b, c",
                speech="Here you can see a right triangle with sides labeled a, b, and the hypotenuse c.",
                diagram_type="triangle",
                data={"labels": ["a", "b", "c"], "show_height": False}),
        ]),
        _step("Example", [
            _frame("concept",
                text="**Example:** a = 3, b = 4\na² + b² = 9 + 16 = 25\nc = √25 = **5**",
                speech="Let's try an example. If a equals 3 and b equals 4, then c squared equals 25, so c equals 5. This is called a 3-4-5 Pythagorean triple!",
                highlight=["3", "4", "5"]),
            _frame("memory",
                text="🎵 **Trick:** 3-4-5, 5-12-13, 8-15-17\nThese are Pythagorean triples!",
                speech="Remember these common Pythagorean triples — they appear in almost every exam!"),
            _frame("quiz_mcq",
                text="**Quiz:** If a = 5 and b = 12, what is c?",
                speech="Quick quiz! If a equals 5 and b equals 12, what is c?",
                quiz_options=["15", "13", "17", "11"],
                quiz_correct_index=1,
                quiz_model_answer="c = √(25 + 144) = √169 = 13",
                quiz_keywords=["13", "hypotenuse", "169"]),
        ]),
        _step("Summary", [
            _frame("summary",
                text="✅ a² + b² = c²\n✅ c is always the hypotenuse (longest side)\n✅ Works ONLY for right-angled triangles",
                speech="To summarise — remember a squared plus b squared equals c squared. The hypotenuse is always the longest side, opposite the right angle."),
        ]),
    ]),
}

# ── Sample 2: Fractions ───────────────────────────────────────────────────────
SAMPLE_FRACTIONS = {
    "id": "sample_fractions",
    "title": "Understanding Fractions",
    "subject": "Mathematics",
    "chapter": "Number System",
    "steps": json.dumps([
        _step("What is a Fraction?", [
            _frame("concept",
                text="**Fraction** = part of a whole\nNumerator / Denominator\n½ means 1 part out of 2",
                speech="A fraction represents a part of a whole. The top number is the numerator — how many parts we have. The bottom is the denominator — how many equal parts the whole is divided into.",
                highlight=["Numerator", "Denominator"]),
            _frame("diagram",
                text="Comparing fractions: ½ vs ¾",
                speech="Here are fraction bars showing half and three-quarters. You can visually see that three-quarters is larger than one-half.",
                diagram_type="fraction_bar",
                data={"fractions": [{"num": 1, "den": 2}, {"num": 3, "den": 4}], "title": "½ vs ¾"}),
        ]),
        _step("Types of Fractions", [
            _frame("concept",
                text="**Proper:** numerator < denominator → ¾\n**Improper:** numerator > denominator → 5/3\n**Mixed:** 1⅔",
                speech="There are three types. Proper fractions are less than one. Improper fractions are greater than one. Mixed numbers combine a whole number and a fraction.",
                highlight=["Proper", "Improper", "Mixed"]),
            _frame("quiz_mcq",
                text="**Quiz:** Which is a proper fraction?",
                speech="Which of these is a proper fraction?",
                quiz_options=["7/4", "3/3", "2/5", "9/2"],
                quiz_correct_index=2,
                quiz_model_answer="2/5 is a proper fraction because the numerator 2 is less than the denominator 5.",
                quiz_keywords=["2/5", "numerator", "denominator", "less than"]),
        ]),
        _step("Summary", [
            _frame("summary",
                text="✅ Fraction = numerator ÷ denominator\n✅ Proper: numerator < denominator\n✅ Improper: numerator > denominator",
                speech="Remember — fractions represent parts of a whole. Proper fractions are less than one, improper are greater than one."),
        ]),
    ]),
}

# ── Sample 3: Photosynthesis ──────────────────────────────────────────────────
SAMPLE_PHOTOSYNTHESIS = {
    "id": "sample_photosynthesis",
    "title": "Photosynthesis",
    "subject": "Science",
    "chapter": "Biology",
    "steps": json.dumps([
        _step("What is Photosynthesis?", [
            _frame("concept",
                text="**Photosynthesis**\nPlants make food using:\nSunlight + Water + CO₂ → Glucose + O₂",
                speech="Photosynthesis is how plants make their own food. They use sunlight, water, and carbon dioxide to produce glucose sugar and release oxygen as a byproduct.",
                highlight=["Sunlight", "Water", "CO₂", "Glucose", "O₂"]),
            _frame("diagram",
                text="Photosynthesis — step by step",
                speech="Let's look at the steps of photosynthesis — from light absorption all the way to glucose production.",
                diagram_type="flow",
                data={"title": "Photosynthesis", "steps": ["Sunlight absorbed", "Water absorbed", "CO₂ enters", "Glucose made", "O₂ released"]}),
        ]),
        _step("The Equation", [
            _frame("concept",
                text="**Equation:**\n6CO₂ + 6H₂O + light → C₆H₁₂O₆ + 6O₂\nHappens in **chloroplasts**",
                speech="The chemical equation for photosynthesis: 6 molecules of carbon dioxide plus 6 molecules of water, with light energy, produce one glucose molecule and 6 oxygen molecules.",
                highlight=["chloroplasts", "C₆H₁₂O₆"]),
            _frame("memory",
                text="🌿 **Remember:** Light + Water + CO₂ → Sugar + Oxygen\n'Plants cook with sunlight!'",
                speech="Here's a simple way to remember photosynthesis — plants are cooking with sunlight, using water and carbon dioxide as ingredients, and the food they make is glucose."),
            _frame("quiz_mcq",
                text="**Quiz:** Where does photosynthesis occur?",
                speech="Where exactly does photosynthesis take place inside a plant cell?",
                quiz_options=["Mitochondria", "Nucleus", "Chloroplast", "Vacuole"],
                quiz_correct_index=2,
                quiz_model_answer="Photosynthesis occurs in the chloroplast, which contains chlorophyll — the green pigment that captures sunlight.",
                quiz_keywords=["chloroplast", "chlorophyll"]),
        ]),
        _step("Summary", [
            _frame("summary",
                text="✅ Photosynthesis makes glucose from light\n✅ Happens in chloroplasts\n✅ Releases oxygen as byproduct",
                speech="To wrap up — photosynthesis is the process by which plants use sunlight to make food in their chloroplasts, releasing oxygen that we breathe."),
        ]),
    ]),
}

# ── Sample 4: Atom Structure ──────────────────────────────────────────────────
SAMPLE_ATOM = {
    "id": "sample_atom",
    "title": "Structure of an Atom",
    "subject": "Science",
    "chapter": "Chemistry",
    "steps": json.dumps([
        _step("Parts of an Atom", [
            _frame("concept",
                text="**Atom** = smallest unit of matter\nNucleus: Protons (+) + Neutrons (0)\nElectrons (−) orbit the nucleus",
                speech="An atom is the smallest unit of an element. The nucleus in the center contains protons with positive charge and neutrons with no charge. Electrons with negative charge orbit around the nucleus.",
                highlight=["Protons", "Neutrons", "Electrons"]),
            _frame("diagram",
                text="Bohr model — Carbon atom (6 protons)",
                speech="Here is the Bohr model of a carbon atom. You can see the nucleus in the center with electrons orbiting in shells around it.",
                diagram_type="atom",
                data={"symbol": "C", "protons": 6, "neutrons": 6, "shells": [2, 4]}),
        ]),
        _step("Atomic Number & Mass", [
            _frame("concept",
                text="**Atomic Number** = number of protons\n**Mass Number** = protons + neutrons\nCarbon: Z=6, A=12",
                speech="The atomic number tells you how many protons an atom has — this identifies the element. The mass number is the total of protons and neutrons in the nucleus.",
                highlight=["Atomic Number", "Mass Number"]),
            _frame("quiz_mcq",
                text="**Quiz:** Atomic number of oxygen is 8. How many electrons does neutral oxygen have?",
                speech="If oxygen has an atomic number of 8, how many electrons does a neutral oxygen atom have?",
                quiz_options=["6", "8", "16", "4"],
                quiz_correct_index=1,
                quiz_model_answer="A neutral atom has equal protons and electrons. Since oxygen has 8 protons, it also has 8 electrons.",
                quiz_keywords=["8", "protons", "electrons", "neutral"]),
        ]),
        _step("Summary", [
            _frame("summary",
                text="✅ Atom: nucleus + electrons\n✅ Nucleus has protons (+) and neutrons\n✅ Atomic number = proton count",
                speech="Remember — every atom has a nucleus with protons and neutrons, surrounded by orbiting electrons. The number of protons is the atomic number."),
        ]),
    ]),
}

# ── Sample 5: Water Cycle ─────────────────────────────────────────────────────
SAMPLE_WATER_CYCLE = {
    "id": "sample_water_cycle",
    "title": "The Water Cycle",
    "subject": "Science",
    "chapter": "Geography",
    "steps": json.dumps([
        _step("How Water Moves", [
            _frame("concept",
                text="**Water Cycle** = continuous movement of water\nEvaporation → Condensation\nPrecipitation → Collection",
                speech="The water cycle describes the continuous movement of water on Earth. Water evaporates from oceans and lakes, forms clouds, falls as rain or snow, and collects again.",
                highlight=["Evaporation", "Condensation", "Precipitation"]),
            _frame("diagram",
                text="The Water Cycle — 4 stages",
                speech="Here you can see the four stages of the water cycle arranged in a circle, showing how water continuously moves through the environment.",
                diagram_type="cycle",
                data={"title": "Water Cycle", "steps": ["Evaporation", "Condensation", "Precipitation", "Collection"]}),
        ]),
        _step("Each Stage", [
            _frame("concept",
                text="**Evaporation:** Heat turns water → vapor\n**Condensation:** Vapor cools → clouds\n**Precipitation:** Water falls as rain/snow",
                speech="Evaporation happens when the sun's heat turns liquid water into water vapor. This vapor rises, cools, and condenses into clouds. When clouds get heavy enough, precipitation occurs as rain or snow.",
                highlight=["Evaporation", "Condensation", "Precipitation"]),
            _frame("quiz_mcq",
                text="**Quiz:** What drives evaporation?",
                speech="What provides the energy to drive evaporation in the water cycle?",
                quiz_options=["Wind", "Solar energy (Sun)", "Gravity", "Condensation"],
                quiz_correct_index=1,
                quiz_model_answer="The Sun provides solar energy that heats water and converts it from liquid to water vapor — this is evaporation.",
                quiz_keywords=["sun", "solar", "heat", "energy"]),
        ]),
        _step("Summary", [
            _frame("summary",
                text="✅ Sun powers evaporation\n✅ Vapor → clouds (condensation)\n✅ Rain/snow = precipitation",
                speech="The water cycle is powered by the sun. Remember the four stages — evaporation, condensation, precipitation, and collection."),
        ]),
    ]),
}

# ── Sample 6: Newton's Laws of Motion ────────────────────────────────────────
SAMPLE_NEWTON = {
    "id": "sample_newton",
    "title": "Newton's Laws of Motion",
    "subject": "Science",
    "chapter": "Physics",
    "steps": json.dumps([
        _step("First Law — Inertia", [
            _frame("concept",
                text="**Newton's 1st Law:** An object at rest stays at rest; an object in motion stays in motion\n— unless a force acts on it",
                speech="Newton's first law is the law of inertia. An object won't change its motion on its own — you need a force to start it, stop it, or change its direction.",
                highlight=["inertia", "force"]),
            _frame("memory",
                text="🚗 **Trick:** Seatbelt = 1st law!\nWhen car stops, body wants to keep moving",
                speech="Here's a real-life example — when a car suddenly brakes, your body jerks forward. That's inertia! Your body wants to keep moving even though the car stopped."),
        ]),
        _step("Second Law — F = ma", [
            _frame("concept",
                text="**Newton's 2nd Law:**\n**F = m × a**\nForce = Mass × Acceleration",
                speech="The second law says that force equals mass times acceleration. The heavier the object or the faster it accelerates, the more force is needed.",
                highlight=["F = m × a", "Force", "Mass", "Acceleration"]),
            _frame("quiz_mcq",
                text="**Quiz:** If mass = 5 kg and acceleration = 3 m/s², what is the force?",
                speech="Using F equals m times a, if mass is 5 kg and acceleration is 3 metres per second squared, what is the force?",
                quiz_options=["8 N", "2 N", "15 N", "1.67 N"],
                quiz_correct_index=2,
                quiz_model_answer="F = m × a = 5 × 3 = 15 Newtons",
                quiz_keywords=["15", "Newton", "F = ma"]),
        ]),
        _step("Third Law — Action-Reaction", [
            _frame("concept",
                text="**Newton's 3rd Law:**\nEvery action has an equal and opposite reaction\nRocket exhaust → rocket goes UP",
                speech="For every action, there is an equal and opposite reaction. When a rocket expels gas downward, the reaction force pushes the rocket upward.",
                highlight=["action", "opposite reaction"]),
            _frame("summary",
                text="✅ 1st: Inertia (F=0 → no change)\n✅ 2nd: F = ma\n✅ 3rd: Action = Opposite Reaction",
                speech="Three laws to remember — inertia, F equals ma, and every action has an equal opposite reaction. These explain all motion around us!"),
        ]),
    ]),
}

# ── Sample 7: Cell Structure ──────────────────────────────────────────────────
SAMPLE_CELL = {
    "id": "sample_cell",
    "title": "Cell Structure",
    "subject": "Science",
    "chapter": "Biology",
    "steps": json.dumps([
        _step("The Cell", [
            _frame("concept",
                text="**Cell** = basic unit of life\nAll living things are made of cells\nDiscovered by Robert Hooke (1665)",
                speech="The cell is the basic unit of life. Every living organism, from bacteria to blue whales, is made of cells. Robert Hooke was the first to observe cells under a microscope in 1665.",
                highlight=["basic unit of life", "Robert Hooke"]),
            _frame("diagram",
                text="Plant cell — labeled parts",
                speech="Here is a labeled diagram of a plant cell showing its main organelles — each part has a specific function.",
                diagram_type="labeled_diagram",
                data={"center": "Cell", "center_shape": "rect",
                      "parts": ["Nucleus", "Chloroplast", "Cell Wall", "Vacuole", "Mitochondria", "Cell Membrane"]}),
        ]),
        _step("Key Organelles", [
            _frame("concept",
                text="**Nucleus:** Control centre (DNA)\n**Mitochondria:** Energy factory (ATP)\n**Chloroplast:** Photosynthesis (plants only)",
                speech="The nucleus is the control centre containing DNA. Mitochondria produce energy as ATP — they're the powerhouse of the cell. Chloroplasts are found only in plants and perform photosynthesis.",
                highlight=["Nucleus", "Mitochondria", "Chloroplast"]),
            _frame("quiz_mcq",
                text="**Quiz:** Which organelle is called the powerhouse of the cell?",
                speech="Which organelle is known as the powerhouse of the cell?",
                quiz_options=["Nucleus", "Vacuole", "Mitochondria", "Ribosome"],
                quiz_correct_index=2,
                quiz_model_answer="The mitochondria is called the powerhouse of the cell because it produces ATP energy through cellular respiration.",
                quiz_keywords=["mitochondria", "ATP", "energy", "powerhouse"]),
        ]),
        _step("Summary", [
            _frame("summary",
                text="✅ Cell = basic unit of life\n✅ Nucleus = control centre\n✅ Mitochondria = energy\n✅ Chloroplast = photosynthesis (plants)",
                speech="Remember — the cell is the building block of life. Each organelle has a specific job. The nucleus controls, mitochondria power, and chloroplasts in plants make food."),
        ]),
    ]),
}

# ── Sample 8: Types of Triangles ──────────────────────────────────────────────
SAMPLE_TRIANGLES = {
    "id": "sample_triangles",
    "title": "Types of Triangles",
    "subject": "Mathematics",
    "chapter": "Geometry",
    "steps": json.dumps([
        _step("By Sides", [
            _frame("concept",
                text="**Equilateral:** All 3 sides equal (60° each)\n**Isosceles:** 2 sides equal\n**Scalene:** No sides equal",
                speech="Triangles can be classified by their sides. An equilateral triangle has all sides equal with 60 degree angles. Isosceles has two equal sides. Scalene has no equal sides.",
                highlight=["Equilateral", "Isosceles", "Scalene"]),
            _frame("diagram",
                text="Triangle with labeled vertices",
                speech="Here is a triangle with vertices labeled A, B, and C. The sides connecting these vertices can be equal or different depending on the type.",
                diagram_type="triangle",
                data={"labels": ["A", "B", "C"], "show_height": True}),
        ]),
        _step("By Angles", [
            _frame("concept",
                text="**Acute:** All angles < 90°\n**Right:** One angle = 90°\n**Obtuse:** One angle > 90°",
                speech="By angles, a triangle is acute if all angles are less than 90 degrees, right if one angle is exactly 90 degrees, and obtuse if one angle exceeds 90 degrees.",
                highlight=["Acute", "Right", "Obtuse"]),
            _frame("quiz_mcq",
                text="**Quiz:** What is the sum of all angles in a triangle?",
                speech="What is the sum of all three interior angles of any triangle?",
                quiz_options=["90°", "180°", "270°", "360°"],
                quiz_correct_index=1,
                quiz_model_answer="The sum of interior angles of any triangle is always 180 degrees.",
                quiz_keywords=["180", "angles", "sum"]),
        ]),
        _step("Summary", [
            _frame("summary",
                text="✅ By sides: Equilateral / Isosceles / Scalene\n✅ By angles: Acute / Right / Obtuse\n✅ Angle sum = 180°",
                speech="Two ways to classify triangles — by their sides and by their angles. And always remember, the angles of any triangle add up to 180 degrees."),
        ]),
    ]),
}

# ── Sample 9: Sound Waves ─────────────────────────────────────────────────────
SAMPLE_SOUND = {
    "id": "sample_sound",
    "title": "Sound Waves",
    "subject": "Science",
    "chapter": "Physics",
    "steps": json.dumps([
        _step("What is Sound?", [
            _frame("concept",
                text="**Sound** = longitudinal wave\nRequires a medium (cannot travel in vacuum)\nSpeed in air: 343 m/s",
                speech="Sound is a longitudinal mechanical wave — it needs a medium like air, water, or solid to travel. It cannot travel through a vacuum. In air, sound travels at about 343 metres per second.",
                highlight=["longitudinal wave", "medium", "343 m/s"]),
            _frame("diagram",
                text="Sound wave — amplitude and wavelength",
                speech="Here is an animated sine wave representing sound. The amplitude shows the loudness, and the wavelength shows the pitch of the sound.",
                diagram_type="waveform_signal",
                data={"title": "Sound Wave", "wave_type": "sine", "cycles": 2.5, "amplitude": 50,
                      "x_label": "time (s)", "y_label": "pressure", "color": "secondary"}),
        ]),
        _step("Properties", [
            _frame("concept",
                text="**Amplitude** → Loudness (dB)\n**Frequency** → Pitch (Hz)\nHigh frequency = high pitch",
                speech="Amplitude determines loudness — larger amplitude means louder sound measured in decibels. Frequency determines pitch — higher frequency gives a higher pitched sound measured in Hertz.",
                highlight=["Amplitude", "Frequency", "Loudness", "Pitch"]),
            _frame("quiz_mcq",
                text="**Quiz:** Sound cannot travel through:",
                speech="Sound needs a medium to travel. Through which of these can sound NOT travel?",
                quiz_options=["Water", "Steel", "Vacuum", "Air"],
                quiz_correct_index=2,
                quiz_model_answer="Sound cannot travel through a vacuum because there are no particles to vibrate and transmit the wave.",
                quiz_keywords=["vacuum", "medium", "particles"]),
        ]),
        _step("Summary", [
            _frame("summary",
                text="✅ Sound = longitudinal mechanical wave\n✅ Needs a medium to travel\n✅ Amplitude → loudness; Frequency → pitch",
                speech="Sound is a mechanical wave requiring a medium. Amplitude controls loudness and frequency controls pitch. No medium means no sound — complete silence in space!"),
        ]),
    ]),
}

# ── Sample 10: Solar System ───────────────────────────────────────────────────
SAMPLE_SOLAR_SYSTEM = {
    "id": "sample_solar_system",
    "title": "The Solar System",
    "subject": "Science",
    "chapter": "Astronomy",
    "steps": json.dumps([
        _step("Our Solar System", [
            _frame("concept",
                text="**Solar System:** Sun + 8 planets\nPlanets orbit the Sun due to gravity\nMy Very Educated Mother Just Served Us Nachos",
                speech="Our solar system has the Sun at the center with 8 planets orbiting around it. Remember the planets in order using the mnemonic — My Very Educated Mother Just Served Us Nachos.",
                highlight=["Sun", "8 planets", "gravity"]),
            _frame("diagram",
                text="The Solar System — planets in orbit",
                speech="Here is an animated solar system showing the sun at the center and planets orbiting at different speeds. Notice how inner planets orbit faster than outer ones.",
                diagram_type="solar_system",
                data={"planets": [
                    {"name": "Mercury", "color": "#A0A0A0"},
                    {"name": "Venus",   "color": "#FFB74D"},
                    {"name": "Earth",   "color": "#42A5F5"},
                    {"name": "Mars",    "color": "#EF5350"},
                    {"name": "Jupiter", "color": "#FFD54F"},
                    {"name": "Saturn",  "color": "#CE93D8"},
                ]}),
        ]),
        _step("Key Facts", [
            _frame("concept",
                text="**Sun:** 99.8% of solar system mass\n**Jupiter:** Largest planet\n**Mercury:** Closest to Sun, fastest orbit",
                speech="The Sun contains 99.8% of all the mass in our solar system. Jupiter is the largest planet. Mercury, being closest to the Sun, has the shortest orbital period of just 88 Earth days.",
                highlight=["Sun", "Jupiter", "Mercury"]),
            _frame("quiz_mcq",
                text="**Quiz:** Which planet is known as the Red Planet?",
                speech="Which planet in our solar system is called the Red Planet?",
                quiz_options=["Jupiter", "Venus", "Mars", "Saturn"],
                quiz_correct_index=2,
                quiz_model_answer="Mars is called the Red Planet because its surface is covered with iron oxide (rust), giving it a distinctive reddish appearance.",
                quiz_keywords=["Mars", "red", "iron oxide"]),
        ]),
        _step("Summary", [
            _frame("summary",
                text="✅ 8 planets orbit the Sun\n✅ Inner planets: Mercury, Venus, Earth, Mars\n✅ Outer giants: Jupiter, Saturn, Uranus, Neptune",
                speech="Our solar system has 8 planets. The four inner rocky planets are Mercury, Venus, Earth, and Mars. The four outer gas giants are Jupiter, Saturn, Uranus, and Neptune."),
        ]),
    ]),
}


# ── Seeder ─────────────────────────────────────────────────────────────────────

ALL_SAMPLES = [
    SAMPLE_PYTHAGOREAN,
    SAMPLE_FRACTIONS,
    SAMPLE_PHOTOSYNTHESIS,
    SAMPLE_ATOM,
    SAMPLE_WATER_CYCLE,
    SAMPLE_NEWTON,
    SAMPLE_CELL,
    SAMPLE_TRIANGLES,
    SAMPLE_SOUND,
    SAMPLE_SOLAR_SYSTEM,
]


def seed(dry_run: bool = False) -> None:
    import firebase_admin
    from firebase_admin import credentials, firestore as fs

    cred_path = os.path.join(os.path.dirname(__file__), "firebase_serviceaccount.json")
    if not firebase_admin._apps:
        cred = credentials.Certificate(cred_path)
        firebase_admin.initialize_app(cred)

    db = fs.client()

    for sample in ALL_SAMPLES:
        doc_id = sample["id"]
        doc = {
            "title":   sample["title"],
            "subject": sample["subject"],
            "chapter": sample["chapter"],
            "steps_json": sample["steps"],
            "is_sample": True,
            "lang": "en-US",
            "created_at": int(time.time() * 1000),
        }
        if dry_run:
            steps = json.loads(sample["steps"])
            frames_with_svg = sum(
                1 for s in steps
                for f in s.get("frames", [])
                if f.get("svg_html")
            )
            print(f"  [DRY RUN] {doc_id}: {len(steps)} steps, {frames_with_svg} diagram frames with SVG")
        else:
            db.collection("bb_samples").document(doc_id).set(doc)
            steps = json.loads(sample["steps"])
            frames_with_svg = sum(
                1 for s in steps
                for f in s.get("frames", [])
                if f.get("svg_html")
            )
            print(f"  ✅ Seeded {doc_id}: {len(steps)} steps, {frames_with_svg} diagram frames with SVG")


if __name__ == "__main__":
    dry = "--dry-run" in sys.argv
    if dry:
        print("=== DRY RUN — no Firestore writes ===")
    else:
        print("=== Seeding bb_samples to Firestore ===")

    seed(dry_run=dry)
    print("Done.")
