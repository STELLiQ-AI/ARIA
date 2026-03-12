#!/usr/bin/env python3
"""
generate_data.py — Generate high-quality synthetic training data for ARIA 8B fine-tuning.

Produces diverse meeting transcripts + deep analytical JSON summaries using Claude API.
Covers all 4 ARIA templates with the enhanced 6-field schema (includes ai_perspective).

The ai_perspective field is the model's own analytical insight — patterns, risks, blind
spots, and strategic recommendations that go beyond what participants explicitly stated.
This is what makes the fine-tuned model more valuable than a generic summarizer.

Usage:
    python scripts/generate_data.py --count 500 --output data/synthetic_8b.jsonl
    python scripts/generate_data.py --count 100 --template military --output data/military.jsonl
    python scripts/generate_data.py --count 200 --quality high --output data/premium.jsonl

Requires: ANTHROPIC_API_KEY environment variable
Cost estimate:
    standard quality (Haiku transcript + Sonnet summary): ~$0.008/example
    high quality (Sonnet for both): ~$0.015/example
"""
import argparse
import json
import os
import random
import sys
import time

try:
    import anthropic
except ImportError:
    print("ERROR: 'anthropic' package not installed. Run: pip install anthropic")
    sys.exit(1)

from tqdm import tqdm


# ---------------------------------------------------------------------------
# Enhanced ARIA system prompts — 6-field schema with ai_perspective
# ---------------------------------------------------------------------------

SYSTEM_PROMPTS = {
    "retrospective": (
        "You are an expert meeting analysis assistant with deep domain knowledge. "
        "Your task is to analyze meeting or recording transcripts and produce a structured "
        "summary with genuine analytical insight.\n\n"
        "Generate a JSON object with exactly these six fields:\n"
        "{\n"
        '  "title": "<A concise, descriptive title in Title Case>",\n'
        '  "what_was_planned": "<The stated purpose, goals, agenda, or objectives. What were participants trying to accomplish?>",\n'
        '  "what_happened": "<Key events, decisions, outcomes, and turning points. What actually occurred vs. what was planned?>",\n'
        '  "why_it_happened": "<Root causes, contributing factors, constraints, and context. What drove the outcomes — both stated and unstated?>",\n'
        '  "how_to_improve": "<Specific, actionable recommendations with owners and timelines where possible. What concrete steps should be taken?>",\n'
        '  "ai_perspective": "<Your independent analytical assessment. Identify patterns, risks, blind spots, or strategic insights the participants may not have explicitly recognized. Connect observations to broader principles, flag potential second-order effects, and offer a candid evaluation of what was discussed vs. what was avoided.>"\n'
        "}\n\n"
        "Rules:\n"
        "- Title: Title Case, specific and descriptive (not generic).\n"
        "- Each field should be 2-5 sentences with substantive content, not filler.\n"
        "- what_happened should contrast plan vs. reality when applicable.\n"
        "- why_it_happened should identify root causes, not just restate symptoms.\n"
        "- how_to_improve must include specific actions, not vague aspirations.\n"
        "- ai_perspective must add genuine analytical value — do NOT restate other fields. "
        "This is your expert assessment of what the participants may have missed, "
        "underlying dynamics, risks they haven't considered, or strategic connections "
        "to broader patterns. Be direct and candid.\n"
        "- Attribute points to specific speakers when the transcript identifies them.\n"
        "- Output ONLY the JSON object, no additional text."
    ),
    "military": (
        "You are a military operations analyst specializing in After Action Reviews (AARs) "
        "following TC 7-0.1 doctrine. Your task is to analyze mission debriefs and training "
        "exercise transcripts to produce structured AARs with tactical and strategic insight.\n\n"
        "Generate a JSON object with exactly these six fields:\n"
        "{\n"
        '  "title": "<Mission/exercise designation and brief description in Title Case>",\n'
        '  "what_was_planned": "<Commander\'s intent, mission objectives, scheme of maneuver, task organization, and timeline. Reference the OPORD/FRAGO when mentioned.>",\n'
        '  "what_happened": "<Chronological execution summary: key events, decision points, friction points, enemy contact, and actual outcomes vs. planned objectives. Include BDA when applicable.>",\n'
        '  "why_it_happened": "<Analysis of contributing factors: leadership decisions, terrain effects, equipment performance, communication breakdowns, logistics constraints, enemy TTPs, and weather impacts.>",\n'
        '  "how_to_improve": "<Specific sustains (what to continue doing) and improves (what to change). Include training recommendations, SOP updates, equipment requests, and leader development points with responsible parties.>",\n'
        '  "ai_perspective": "<Your independent tactical/strategic assessment. Identify doctrine gaps, leadership dynamics not discussed openly, systemic issues across multiple AARs, risk factors for future operations, and how lessons from this action connect to broader operational patterns. Flag any groupthink, blame-shifting, or critical observations the unit may be avoiding. Be direct — this assessment is for senior leadership.>"\n'
        "}\n\n"
        "Rules:\n"
        "- Use proper military terminology (METT-TC, TLP, OAKOC, etc.) naturally.\n"
        "- Reference doctrine (FM 3-0, ATP 3-21.8, TC 7-0.1) when relevant.\n"
        "- Each field should be 3-6 sentences with operational specificity.\n"
        "- ai_perspective must go beyond the participants' stated observations.\n"
        "- Attribute observations to specific participants by rank/name.\n"
        "- Output ONLY the JSON object, no additional text."
    ),
    "incident": (
        "You are an incident response and root cause analysis specialist. "
        "Analyze incident review transcripts and produce structured postmortem reports "
        "with systemic insight.\n\n"
        "Generate a JSON object with exactly these six fields:\n"
        "{\n"
        '  "title": "<Incident identifier and brief description in Title Case>",\n'
        '  "what_was_planned": "<System state before incident: expected behavior, SLOs/SLAs, deployed changes, and operational context.>",\n'
        '  "what_happened": "<Incident timeline: detection, escalation, investigation steps, mitigation actions, resolution, and impact metrics (duration, users affected, revenue impact).>",\n'
        '  "why_it_happened": "<Root cause analysis: contributing factors at technical, process, and organizational levels. Identify the causal chain, not just the trigger.>",\n'
        '  "how_to_improve": "<Specific remediation items with owners and deadlines. Categorize as: immediate fixes, short-term hardening, long-term systemic improvements.>",\n'
        '  "ai_perspective": "<Your systemic analysis. Identify patterns across incidents, organizational dynamics that enabled this failure, monitoring gaps, cultural factors (blameless vs. blame culture), and whether the proposed fixes address root causes or just symptoms. Flag any normalization of deviance.>"\n'
        "}\n\n"
        "Rules:\n"
        "- Each field should be 3-5 sentences with operational specificity.\n"
        "- ai_perspective must identify systemic patterns, not restate the timeline.\n"
        "- Output ONLY the JSON object, no additional text."
    ),
    "simple": (
        "You are a clear, insightful meeting summarizer. Analyze transcripts and produce "
        "structured summaries with useful analytical perspective.\n\n"
        "Generate a JSON object with exactly these six fields:\n"
        "{\n"
        '  "title": "<A concise, descriptive title in Title Case>",\n'
        '  "what_was_planned": "<What was the purpose of this conversation or meeting?>",\n'
        '  "what_happened": "<What were the main topics discussed and key takeaways?>",\n'
        '  "why_it_happened": "<What important context or background was mentioned?>",\n'
        '  "how_to_improve": "<What are the action items and next steps?>",\n'
        '  "ai_perspective": "<Your honest assessment: what went well in this discussion, what was left unresolved, any concerns or opportunities the group may not have fully addressed, and whether the action items are likely to be effective.>"\n'
        "}\n\n"
        "Rules:\n"
        "- Use plain, everyday language. Be direct and helpful.\n"
        "- Each field should be 1-3 sentences. Be concise but substantive.\n"
        "- ai_perspective should add practical insight, not just summarize.\n"
        "- Output ONLY the JSON object, no additional text."
    ),
}


# ---------------------------------------------------------------------------
# Massively expanded scenario pools
# ---------------------------------------------------------------------------

MILITARY_SCENARIOS = [
    # Tactical operations
    "infantry platoon patrol debrief after a dismounted area reconnaissance in urban terrain",
    "mounted patrol after-action review following route clearance operations on MSR Tampa",
    "squad-level react to contact drill AAR at the National Training Center",
    "company-level deliberate attack rehearsal and execution review",
    "air assault planning and execution AAR for a battalion-level operation",
    "convoy security operation debrief after an IED encounter on Route Irish",
    "joint patrol debrief with host nation forces in a stability operations environment",
    "sniper team overwatch position selection and engagement AAR",
    "combat outpost defense exercise AAR with mortar and indirect fire coordination",
    "military operations on urban terrain (MOUT) clearing exercise review",
    "counter-IED lane training AAR focusing on 5/25 meter checks and ground sign awareness",
    "platoon ambush execution review emphasizing actions on the objective",
    "tactical checkpoint operations AAR with escalation of force procedures",
    "night vision operations training exercise debrief",
    "reconnaissance and surveillance handoff between scout and infantry elements",
    # Logistics and support
    "forward operating base resupply convoy debrief covering fuel and ammunition distribution",
    "field maintenance collection point operations review",
    "combat medic mass casualty exercise AAR with triage and MEDEVAC coordination",
    "communications equipment PMCS and radio net operations review",
    "water purification and distribution point operations debrief",
    "helicopter landing zone setup and rotary wing integration AAR",
    # Leadership and training
    "officer professional development session on mission command philosophy",
    "NCO development program after-action review covering squad leader responsibilities",
    "pre-deployment training readiness assessment for a deploying brigade",
    "lessons learned integration meeting from a recent combat training center rotation",
    "battalion commander's assessment of company-level collective training proficiency",
    "military police law enforcement operations training review",
    "chemical biological radiological nuclear defense exercise AAR",
    "combined arms maneuver training exercise hot wash at brigade level",
    "joint terminal attack controller close air support coordination debrief",
    "tactical operations center battle drill and shift change procedures review",
    "personnel recovery and downed aircraft procedures exercise AAR",
    "civil affairs team engagement with village elders debrief",
    "psychological operations product dissemination and effectiveness review",
    "military intelligence collection and analysis cycle AAR",
    "engineer route clearance and obstacle emplacement operations review",
    "artillery fire mission processing and execution time analysis",
    "forward observer call for fire qualification AAR",
    "combat lifesaver recertification course hot wash",
    "force protection and base defense exercise debrief",
]

RETROSPECTIVE_SCENARIOS = [
    # Software engineering
    "two-week sprint retrospective for a mobile app team that missed their deadline",
    "platform engineering team post-incident review after a cascading database failure",
    "DevOps team retrospective on CI/CD pipeline migration from Jenkins to GitHub Actions",
    "security team quarterly review after discovering a critical vulnerability in production",
    "data engineering sprint retro after a failed ETL pipeline migration",
    "frontend team review of a major UI redesign that received mixed user feedback",
    "machine learning team model deployment retrospective covering A/B test results",
    "backend team API versioning strategy review after breaking client integrations",
    # Business operations
    "sales team quarterly pipeline review with CRM data analysis and forecast adjustment",
    "product launch post-mortem for a feature that underperformed market expectations",
    "marketing campaign performance review with attribution analysis and budget reallocation",
    "customer success team churn analysis review with retention strategy discussion",
    "supply chain disruption response meeting after a key supplier factory shutdown",
    "warehouse operations efficiency review comparing automated vs manual picking",
    "startup board meeting with investor update on burn rate and runway",
    "M&A integration team progress review covering system consolidation challenges",
    "quality assurance team review of defect escape rate trends over the past quarter",
    "procurement team vendor evaluation and contract renewal negotiations review",
    # Education and nonprofit
    "school district superintendent's review of standardized test score improvements",
    "university research lab weekly progress update on NIH grant deliverables",
    "nonprofit fundraising committee review of annual gala results and donor engagement",
    "community health center board review of patient outcome metrics",
    "vocational training program completion rate analysis and curriculum adjustment meeting",
    # Construction and trades
    "construction site safety debrief after a near-miss incident involving scaffolding",
    "residential development project milestone review with contractor schedule analysis",
    "manufacturing plant quality control review after product recall notification",
    "electrical contractor project closeout lessons learned review",
    "automotive factory production line efficiency review after robotic cell integration",
]

INCIDENT_SCENARIOS = [
    "production database outage caused by an unplanned schema migration during peak traffic",
    "cloud infrastructure failure review after AWS region connectivity loss affected services",
    "security incident postmortem after detection of unauthorized API access using stolen credentials",
    "payment processing system failure during Black Friday affecting $2.3M in transactions",
    "CDN configuration error that caused a 4-hour global outage for the mobile application",
    "Kubernetes cluster resource exhaustion incident caused by a memory leak in a new deployment",
    "DNS propagation failure review after a routine domain registrar change went wrong",
    "data pipeline corruption incident that produced incorrect financial reports for 3 days",
    "third-party API dependency failure cascade that degraded user experience for 12 hours",
    "load balancer misconfiguration that caused uneven traffic distribution and partial outage",
    "certificate expiration incident that blocked all HTTPS traffic for 90 minutes",
    "deployment rollback failure review after a feature flag caused unexpected behavior",
    "monitoring gap analysis after an incident went undetected for 6 hours",
    "ransomware response exercise debrief with tabletop scenario lessons learned",
    "network segmentation failure that allowed lateral movement during a penetration test",
]

SIMPLE_SCENARIOS = [
    "project kickoff meeting for a new company website redesign",
    "weekly team standup covering blockers and priorities for the sprint",
    "all-hands company meeting announcing organizational changes and Q1 results",
    "one-on-one between manager and direct report discussing career development",
    "cross-functional alignment meeting between product, engineering, and design teams",
    "client onboarding call walking through implementation timeline and requirements",
    "budget planning meeting for the upcoming fiscal year with department heads",
    "new hire orientation session covering company culture and expectations",
    "partnership discussion between two companies exploring a joint venture",
    "community association meeting about proposed neighborhood improvements",
    "parent-teacher conference discussing student academic progress and behavior",
    "volunteer coordination meeting for a local food bank expansion",
    "homeowners association annual meeting covering budget and maintenance priorities",
    "church leadership meeting planning community outreach programs for summer",
    "youth sports league organizational meeting for the upcoming season",
]

SCENARIO_POOLS = {
    "military": MILITARY_SCENARIOS,
    "retrospective": RETROSPECTIVE_SCENARIOS,
    "incident": INCIDENT_SCENARIOS,
    "simple": SIMPLE_SCENARIOS,
}

# Map scenario categories to system prompt templates
TEMPLATE_MAP = {
    "military": "military",
    "retrospective": "retrospective",
    "incident": "incident",
    "simple": "simple",
}

# ---------------------------------------------------------------------------
# Speaker name pools by domain
# ---------------------------------------------------------------------------

MILITARY_SPEAKERS = [
    "CPT Rodriguez", "1LT Chen", "SFC Williams", "SSG Patel", "SGT Thompson",
    "SGT Davis", "CPL Martinez", "PFC Johnson", "PFC Kim", "SPC Garcia",
    "MAJ Harrison", "LTC Brooks", "CSM Reeves", "1SG Mitchell", "MSG Ortiz",
    "2LT Foster", "WO1 Patterson", "CW2 Sullivan", "CW3 Nakamura",
    "COL Westbrook", "BG Sterling", "SGM Richardson",
]

CORPORATE_SPEAKERS = [
    "Stephen", "Maria", "James", "Sarah", "David", "Lisa", "Michael", "Jennifer",
    "Robert", "Emily", "Thomas", "Amanda", "Daniel", "Jessica", "Christopher",
    "Ashley", "Matthew", "Megan", "Andrew", "Rachel", "Brian", "Nicole",
    "Kevin", "Lauren", "Priya", "Wei", "Fatima", "Olga", "Carlos", "Aisha",
]

SPEAKER_POOLS = {
    "military": MILITARY_SPEAKERS,
    "retrospective": CORPORATE_SPEAKERS,
    "incident": CORPORATE_SPEAKERS,
    "simple": CORPORATE_SPEAKERS,
}


# ---------------------------------------------------------------------------
# Transcript generation prompt
# ---------------------------------------------------------------------------

def build_transcript_prompt(template: str, scenario: str, speakers: list,
                             num_turns: int, duration_minutes: int) -> str:
    """Build the prompt for generating a realistic transcript."""

    domain_guidance = ""
    if template == "military":
        domain_guidance = (
            "- Use authentic military terminology: grid coordinates, unit designations "
            "(e.g., '2nd PLT, Alpha Company'), equipment names (M4, M240B, MRAP, LMTV), "
            "doctrinal references (METT-TC, TLP, IPB), radio procedures, and tactical language.\n"
            "- Include specific operational details: phase lines, objective names, time hacks, "
            "grid coordinates (e.g., 'grid MB 1234 5678'), casualty status, ammunition expenditure.\n"
            "- Show realistic friction: miscommunication, equipment failures, weather impacts, "
            "terrain challenges, timing delays, and leadership decisions under pressure.\n"
            "- Speakers should use rank-appropriate communication patterns. NCOs are direct, "
            "officers reference intent and scheme of maneuver, junior enlisted give ground truth.\n"
        )
    elif template == "incident":
        domain_guidance = (
            "- Include specific technical details: error messages, metric values, timestamps, "
            "service names, configuration details, and command outputs.\n"
            "- Show the investigation process: hypotheses formed and tested, red herrings, "
            "escalation decisions, and communication with stakeholders.\n"
            "- Reference monitoring tools, dashboards, and alerting thresholds.\n"
        )

    return (
        f"Generate a highly realistic, detailed meeting transcript for:\n"
        f"{scenario}\n\n"
        f"Requirements:\n"
        f"- {len(speakers)} speakers: {', '.join(speakers)}\n"
        f"- {num_turns} dialogue turns over ~{duration_minutes} minutes\n"
        f"- Include timestamps like [0:00], [0:45], [1:30], etc.\n"
        f"- Format: [timestamp] Speaker Name: dialogue text\n"
        f"- Make it feel authentic with natural speech patterns — hesitations, "
        f"interruptions, tangents, agreements, and disagreements.\n"
        f"- Include SPECIFIC details: numbers, names, dates, locations, "
        f"technical terms, and actionable items. Avoid vague generalities.\n"
        f"- Show realistic group dynamics: someone dominating, someone quiet, "
        f"disagreements resolved or tabled, side conversations.\n"
        f"- Include at least 2-3 points of tension, disagreement, or unresolved issues.\n"
        f"{domain_guidance}"
        f"- Output ONLY the transcript, no other text."
    )


# ---------------------------------------------------------------------------
# Core generation logic
# ---------------------------------------------------------------------------

def generate_example(client, template: str, scenario: str,
                      quality: str = "standard") -> dict | None:
    """Generate a single transcript + deep analytical summary pair."""

    speaker_pool = SPEAKER_POOLS.get(template, CORPORATE_SPEAKERS)
    num_speakers = random.randint(3, 6)
    speakers = random.sample(speaker_pool, min(num_speakers, len(speaker_pool)))
    duration_minutes = random.randint(5, 20)
    num_turns = random.randint(15, 40)

    # Model selection based on quality tier
    transcript_model = "claude-haiku-4-5-20251001"
    summary_model = "claude-sonnet-4-6-20250131" if quality == "high" else "claude-haiku-4-5-20251001"

    transcript_prompt = build_transcript_prompt(
        template, scenario, speakers, num_turns, duration_minutes
    )

    try:
        # Step 1: Generate transcript
        transcript_resp = client.messages.create(
            model=transcript_model,
            max_tokens=4096,
            messages=[{"role": "user", "content": transcript_prompt}],
        )
        transcript = transcript_resp.content[0].text.strip()

        if len(transcript) < 200:
            return None

        # Step 2: Generate deep analytical summary with ai_perspective
        system_prompt = SYSTEM_PROMPTS[TEMPLATE_MAP[template]]
        summary_resp = client.messages.create(
            model=summary_model,
            max_tokens=1500,
            system=system_prompt,
            messages=[{
                "role": "user",
                "content": f"Summarize this meeting transcript:\n\n{transcript}"
            }],
        )
        summary_json = summary_resp.content[0].text.strip()

        # Strip markdown code fences if present
        if summary_json.startswith("```"):
            lines = summary_json.split("\n")
            summary_json = "\n".join(lines[1:-1] if lines[-1].strip() == "```" else lines[1:])
            summary_json = summary_json.strip()

        # Validate JSON and required fields
        parsed = json.loads(summary_json)
        required_fields = ["title", "what_was_planned", "what_happened",
                          "why_it_happened", "how_to_improve", "ai_perspective"]
        if not all(k in parsed for k in required_fields):
            # Try to salvage — if only ai_perspective is missing, that's the old schema
            if all(k in parsed for k in required_fields[:5]) and "ai_perspective" not in parsed:
                return None  # Don't accept 5-field output for 8B training
            return None

        # Quality gate: reject shallow ai_perspective
        perspective = parsed.get("ai_perspective", "")
        if len(perspective) < 80:
            return None  # Too short to be genuinely insightful

        return {
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": f"Summarize this meeting transcript:\n\n{transcript}"},
                {"role": "assistant", "content": summary_json},
            ]
        }

    except json.JSONDecodeError:
        return None
    except anthropic.RateLimitError:
        print("\n  Rate limited — waiting 30s...")
        time.sleep(30)
        return None
    except Exception as e:
        print(f"\n  API error: {e}")
        return None


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        description="Generate synthetic ARIA training data (enhanced 6-field schema)"
    )
    parser.add_argument("--count", type=int, default=500,
                        help="Number of examples to generate (default: 500)")
    parser.add_argument("--output", default="data/synthetic_8b.jsonl",
                        help="Output JSONL file path")
    parser.add_argument("--template", default="all",
                        help="Template: military, retrospective, soap, legal, incident, simple, or all")
    parser.add_argument("--quality", default="standard", choices=["standard", "high"],
                        help="standard=Haiku+Haiku (~$0.004/ex), high=Haiku+Sonnet (~$0.012/ex)")
    parser.add_argument("--append", action="store_true",
                        help="Append to existing file instead of overwriting")
    parser.add_argument("--military-weight", type=float, default=0.35,
                        help="Fraction of examples allocated to military scenarios (default: 0.35)")
    args = parser.parse_args()

    api_key = os.environ.get("ANTHROPIC_API_KEY")
    if not api_key:
        print("ERROR: ANTHROPIC_API_KEY environment variable not set.")
        print("Get your key at https://console.anthropic.com/")
        sys.exit(1)

    client = anthropic.Anthropic(api_key=api_key)

    # Determine templates and distribution
    if args.template == "all":
        templates = list(SCENARIO_POOLS.keys())
    else:
        if args.template not in SCENARIO_POOLS:
            print(f"ERROR: Unknown template '{args.template}'. "
                  f"Available: {list(SCENARIO_POOLS.keys())}")
            sys.exit(1)
        templates = [args.template]

    # Weighted distribution: military gets extra allocation
    if args.template == "all":
        military_count = int(args.count * args.military_weight)
        remaining = args.count - military_count
        other_templates = [t for t in templates if t != "military"]
        per_other = remaining // len(other_templates) if other_templates else 0
        distribution = {"military": military_count}
        for i, t in enumerate(other_templates):
            distribution[t] = per_other + (1 if i < remaining % len(other_templates) else 0)
    else:
        distribution = {args.template: args.count}

    cost_per = 0.012 if args.quality == "high" else 0.004
    total_cost = args.count * cost_per

    print(f"\n{'='*60}")
    print(f"ARIA 8B Synthetic Data Generator (6-field schema)")
    print(f"{'='*60}")
    print(f"Total examples: {args.count}")
    print(f"Quality: {args.quality}")
    print(f"Distribution:")
    for t, c in sorted(distribution.items(), key=lambda x: -x[1]):
        print(f"  {t}: {c}")
    print(f"Est. cost: ~${total_cost:.2f}")
    print(f"Est. time: ~{args.count * 5 // 60} min")
    print(f"Output: {args.output}")
    print(f"{'='*60}\n")

    from pathlib import Path
    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    mode = "a" if args.append else "w"
    generated = 0
    failed = 0

    with open(output_path, mode, encoding="utf-8") as f:
        for template, target in sorted(distribution.items(), key=lambda x: -x[1]):
            scenarios = SCENARIO_POOLS[template]
            template_generated = 0

            print(f"\nGenerating {target} '{template}' examples...")

            pbar = tqdm(total=target, desc=f"  {template}")
            attempts = 0
            max_attempts = target * 3

            while template_generated < target and attempts < max_attempts:
                scenario = random.choice(scenarios)
                example = generate_example(client, template, scenario, args.quality)
                attempts += 1

                if example:
                    f.write(json.dumps(example, ensure_ascii=False) + "\n")
                    f.flush()
                    template_generated += 1
                    generated += 1
                    pbar.update(1)
                else:
                    failed += 1

                # Rate limiting
                time.sleep(0.2 if args.quality == "standard" else 0.5)

            pbar.close()

    print(f"\n{'='*60}")
    print(f"Done! Generated {generated} examples ({failed} failures)")
    print(f"Output: {args.output}")
    print(f"{'='*60}")


if __name__ == "__main__":
    main()
