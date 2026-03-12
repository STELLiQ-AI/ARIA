#!/usr/bin/env python3
"""
validate.py — Test the fine-tuned ARIA GGUF model with sample transcripts.

Loads the GGUF model using llama-cpp-python and runs inference on test
transcripts to verify output quality before deploying to the device.

Usage:
    python scripts/validate.py --model output/aria-aar-8b-q4_k_m.gguf
    python scripts/validate.py --model output/aria-aar-8b-q4_k_m.gguf --verbose
    python scripts/validate.py --model output/aria-aar-8b-q4_k_m.gguf --test-file data/test_cases.jsonl
"""
import argparse
import json
import sys
import time
from pathlib import Path

try:
    from llama_cpp import Llama
except ImportError:
    print("ERROR: 'llama-cpp-python' not installed. Run: pip install llama-cpp-python")
    sys.exit(1)


# ---------------------------------------------------------------------------
# ARIA system prompt (RETROSPECTIVE — primary template)
# ---------------------------------------------------------------------------
SYSTEM_PROMPT = (
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
)


# ---------------------------------------------------------------------------
# Built-in test transcripts
# ---------------------------------------------------------------------------
TEST_TRANSCRIPTS = [
    {
        "name": "Sprint Retro (multi-speaker)",
        "transcript": (
            "[0:00] Stephen: Alright team, let's do the sprint retro. We had three goals: "
            "finish the auth module, payment integration, and dashboard redesign.\n"
            "[0:15] Maria: Auth is done. OAuth with Google and Apple. Took two extra days "
            "because Apple docs were awful.\n"
            "[0:28] James: Payments are seventy percent. Stripe webhook signatures don't "
            "match between sandbox and production. Got a workaround but need more testing.\n"
            "[0:45] Stephen: Dashboard?\n"
            "[0:47] Lisa: Dashboard is complete. New analytics panel is live. Response time "
            "dropped from 800ms to 200ms after we switched to server-side rendering.\n"
            "[1:02] Stephen: Great work everyone. Let's get payments wrapped up by Thursday. "
            "Maria, can you document the Apple OAuth issues for the wiki?"
        ),
    },
    {
        "name": "Medical Consult (2 speakers)",
        "transcript": (
            "[0:00] Dr. Chen: Good morning. How have you been feeling since we adjusted "
            "your blood pressure medication last month?\n"
            "[0:08] Patient: Much better actually. The dizziness went away after about a week. "
            "But I've been having some trouble sleeping. Waking up around 3 AM.\n"
            "[0:22] Dr. Chen: That could be related to the lisinopril. Your blood pressure "
            "readings from the home monitor look good though. 128 over 82 average?\n"
            "[0:35] Patient: Yes, around there. Sometimes lower in the morning.\n"
            "[0:40] Dr. Chen: Good. Let's try taking the medication in the morning instead "
            "of at night. That might help with the sleep issues. I'd also like to order "
            "some bloodwork to check your kidney function. Come back in six weeks."
        ),
    },
    {
        "name": "Short Solo Recording",
        "transcript": (
            "[0:00] Stephen: Quick note to self. Need to follow up with the vendor about "
            "the delayed shipment. Order number is PO-2847. Expected delivery was last "
            "Friday. Also need to update the project timeline in Monday dot com and send "
            "the revised budget to finance by end of day."
        ),
    },
    {
        "name": "Legal Discussion",
        "transcript": (
            "[0:00] Attorney Mitchell: We're here to discuss the Patterson lease dispute. "
            "The tenant is claiming the security deposit was wrongfully withheld.\n"
            "[0:12] Counsel Roberts: Our client documented all damages with photos taken "
            "at move-out inspection. We have receipts for repairs totaling thirty-two hundred.\n"
            "[0:25] Attorney Mitchell: The lease specifies normal wear and tear exclusions. "
            "Some of these charges look like they fall under that category. The carpet "
            "replacement for example — carpet was eight years old.\n"
            "[0:40] Counsel Roberts: Fair point on the carpet. We'd be willing to reduce "
            "the claim by twelve hundred for the carpet. That leaves two thousand for the "
            "wall damage and kitchen repairs which are clearly beyond normal wear.\n"
            "[0:55] Attorney Mitchell: Let me discuss with my client. If we can settle at "
            "two thousand, we'd agree to drop the small claims filing."
        ),
    },
    {
        "name": "Team Standup (3 speakers)",
        "transcript": (
            "[0:00] David: Morning standup. What's everyone working on?\n"
            "[0:05] Sarah: I'm finishing the database migration script. Should be done by "
            "lunch. Then I'll start on the API endpoint tests.\n"
            "[0:15] Kevin: I'm blocked on the deployment pipeline. The staging environment "
            "is down since last night. I've pinged DevOps but no response yet.\n"
            "[0:25] David: I'll escalate that. Kevin, can you work on the documentation "
            "in the meantime? Sarah, make sure the migration has a rollback script.\n"
            "[0:35] Sarah: Already got the rollback covered.\n"
            "[0:38] David: Good. Let's sync again at 3 PM."
        ),
    },
    {
        "name": "Budget Review (detailed numbers)",
        "transcript": (
            "[0:00] Jennifer: Q3 budget review. Total spending was 4.2 million against "
            "a budget of 3.8 million. We're over by about ten percent.\n"
            "[0:15] Robert: Marketing drove most of the overage. The digital campaign "
            "cost 380K more than planned because we extended the social media push.\n"
            "[0:28] Jennifer: Did we see ROI on that?\n"
            "[0:30] Robert: Yes. Lead gen was up 45 percent and cost per acquisition "
            "actually dropped from 120 to 85 dollars.\n"
            "[0:42] Jennifer: So the overspend was justified. What about engineering?\n"
            "[0:48] Thomas: Engineering was under budget by 60K. We delayed two "
            "contractor hires to Q4. That'll catch up.\n"
            "[0:58] Jennifer: OK. For Q4, let's increase the marketing allocation by "
            "200K and keep engineering flat. I'll need revised numbers by Friday."
        ),
    },
    {
        "name": "Minimal Input (edge case)",
        "transcript": "Hello, testing one two three.",
    },
    {
        "name": "Military AAR",
        "transcript": (
            "[0:00] Lt. Parker: After action review for today's land navigation exercise. "
            "Mission was a twelve-click movement to rally point Bravo with four checkpoints.\n"
            "[0:18] Sgt. Rodriguez: Team Alpha completed in 3 hours 40 minutes. We missed "
            "checkpoint three because the terrain feature on the map didn't match reality. "
            "The draw was much steeper than the contour lines suggested.\n"
            "[0:35] Cpl. Davis: Team Bravo finished in 4 hours 10 minutes. PFC Johnson "
            "twisted his ankle at the stream crossing near grid 387 291. We lost twenty "
            "minutes treating and re-distributing his load.\n"
            "[0:52] Lt. Parker: Lessons learned: always do a terrain analysis brief before "
            "stepping off. And we need to add ankle braces to the recommended gear list. "
            "Sgt. Rodriguez, update the SOP with the stream crossing procedures."
        ),
    },
    {
        "name": "Empty/Noise (edge case)",
        "transcript": "[unintelligible] ... [background noise] ... [silence]",
    },
    {
        "name": "Customer Support Review",
        "transcript": (
            "[0:00] Angela: Weekly support metrics review. We handled 847 tickets this week, "
            "up from 720 last week. Average resolution time is 4.2 hours.\n"
            "[0:15] Brian: The spike is from the payment processing bug on Tuesday. That "
            "single issue generated 180 tickets in four hours.\n"
            "[0:28] Angela: Customer satisfaction score dropped to 3.2 out of 5 from 4.1. "
            "Clearly that incident hurt us.\n"
            "[0:38] Brian: We've added a status page alert for payment issues now. Should "
            "catch it faster next time. Also proposing we add a self-service refund option "
            "so customers don't have to wait for an agent.\n"
            "[0:52] Angela: Good idea. Write up the proposal and I'll review it Monday. "
            "Let's also do a postmortem on the payment bug with engineering."
        ),
    },
]


# ---------------------------------------------------------------------------
# Scoring
# ---------------------------------------------------------------------------

def score_output(raw_output: str) -> dict:
    """Score a model output for quality."""
    result = {
        "valid_json": False,
        "has_all_fields": False,
        "title_case": False,
        "fields_populated": 0,
        "total_chars": len(raw_output),
        "score": 0.0,
    }

    try:
        # Try to extract JSON from output (model might add extra text)
        text = raw_output.strip()
        start = text.find("{")
        end = text.rfind("}") + 1
        if start >= 0 and end > start:
            text = text[start:end]

        obj = json.loads(text)
        result["valid_json"] = True

        required = ["title", "what_was_planned", "what_happened",
                    "why_it_happened", "how_to_improve", "ai_perspective"]
        present = [k for k in required if k in obj and obj[k]]
        result["has_all_fields"] = len(present) == len(required)
        result["fields_populated"] = len(present)

        # Title case check
        title = obj.get("title", "")
        if title:
            words = title.split()
            minor_words = {"a", "an", "the", "and", "or", "but", "in", "on",
                          "at", "to", "for", "of", "with", "by"}
            title_case_ok = all(
                w[0].isupper() or w.lower() in minor_words
                for w in words if w
            )
            result["title_case"] = title_case_ok

        # Compute score (0-100)
        score = 0
        if result["valid_json"]:
            score += 25
        score += result["fields_populated"] * 9  # 54 max (6 fields)
        if result["title_case"]:
            score += 8
        if result["has_all_fields"]:
            score += 13  # Bonus for completeness (all 6 fields)
        result["score"] = min(score, 100)

        result["parsed"] = obj

    except (json.JSONDecodeError, TypeError):
        pass

    return result


def main():
    parser = argparse.ArgumentParser(description="Validate ARIA GGUF model")
    parser.add_argument("--model", required=True, help="Path to GGUF model file")
    parser.add_argument("--verbose", action="store_true", help="Show full model output")
    parser.add_argument("--test-file", help="Custom test file (JSONL with 'transcript' field)")
    parser.add_argument("--n-gpu-layers", type=int, default=0,
                        help="GPU layers for inference (default: 0 = CPU)")
    parser.add_argument("--max-tokens", type=int, default=512,
                        help="Max output tokens (default: 512)")
    args = parser.parse_args()

    model_path = Path(args.model)
    if not model_path.exists():
        print(f"ERROR: Model not found: {args.model}")
        sys.exit(1)

    # Load test cases
    if args.test_file:
        test_cases = []
        with open(args.test_file, "r", encoding="utf-8") as f:
            for i, line in enumerate(f):
                obj = json.loads(line)
                test_cases.append({
                    "name": obj.get("name", f"Test {i+1}"),
                    "transcript": obj.get("transcript", obj.get("text", "")),
                })
    else:
        test_cases = TEST_TRANSCRIPTS

    print(f"\n{'='*60}")
    print(f"ARIA Model Validation")
    print(f"{'='*60}")
    print(f"Model: {model_path.name} ({model_path.stat().st_size / 1e6:.1f} MB)")
    print(f"Tests: {len(test_cases)}")
    print(f"{'='*60}\n")

    # Load model
    print("Loading model...")
    llm = Llama(
        model_path=str(model_path),
        n_ctx=4096,
        n_gpu_layers=args.n_gpu_layers,
        verbose=False,
    )
    print("Model loaded.\n")

    # Run tests
    scores = []
    total_time = 0

    for i, test in enumerate(test_cases):
        print(f"--- Test {i+1}/{len(test_cases)}: {test['name']} {'-'*30}")

        # Build Llama 3.1 Instruct prompt
        prompt = (
            "<|start_header_id|>system<|end_header_id|>\n\n"
            f"{SYSTEM_PROMPT}<|eot_id|>"
            "<|start_header_id|>user<|end_header_id|>\n\n"
            f"Summarize this meeting transcript:\n\n{test['transcript']}<|eot_id|>"
            "<|start_header_id|>assistant<|end_header_id|>\n\n"
        )

        start = time.time()
        output = llm(
            prompt,
            max_tokens=args.max_tokens,
            stop=["<|eot_id|>", "<|end_of_text|>"],
            temperature=0.1,
            top_p=0.9,
        )
        elapsed = time.time() - start
        total_time += elapsed

        raw_text = output["choices"][0]["text"].strip()
        result = score_output(raw_text)
        scores.append(result["score"])

        # Display result
        status = "PASS" if result["score"] >= 70 else "WARN" if result["score"] >= 40 else "FAIL"
        print(f"  Score: {result['score']:.0f}/100  [{status}]  "
              f"({elapsed:.1f}s, {result['fields_populated']}/6 fields)")

        if result.get("parsed"):
            title = result["parsed"].get("title", "(no title)")
            print(f"  Title: {title}")

        if args.verbose or result["score"] < 70:
            print(f"  Raw output: {raw_text[:300]}{'...' if len(raw_text) > 300 else ''}")

        print()

    # Summary
    avg_score = sum(scores) / len(scores) if scores else 0
    passing = sum(1 for s in scores if s >= 70)
    avg_time = total_time / len(test_cases) if test_cases else 0

    print(f"{'='*60}")
    print(f"VALIDATION RESULTS")
    print(f"{'='*60}")
    print(f"  Average score: {avg_score:.1f}/100")
    print(f"  Passing (>=70): {passing}/{len(scores)}")
    print(f"  Average time: {avg_time:.1f}s per test")
    print(f"  Total time: {total_time:.1f}s")
    print(f"{'='*60}")

    if avg_score >= 70:
        print(f"\n  READY FOR DEPLOYMENT")
        print(f"  Deploy: adb push {args.model} "
              f"/sdcard/Android/data/com.stelliq.aria/files/models/")
    elif avg_score >= 40:
        print(f"\n  MARGINAL — Consider more training data or epochs")
    else:
        print(f"\n  NOT READY — Model needs significant improvement")

    return 0 if avg_score >= 70 else 1


if __name__ == "__main__":
    sys.exit(main())
