import js from "@eslint/js";
import tseslint from "typescript-eslint";

// Baseline lint setup (CI tooling pass) -- deliberately just the non-type-checked "recommended"
// presets, not "strict"/"recommendedTypeChecked". Retrofitting a stricter ruleset onto an existing
// codebase tends to surface a wall of pre-existing violations unrelated to whatever change
// triggered adding lint in the first place; start lenient, tighten later if the team wants to.
export default tseslint.config(
  {
    ignores: ["dist/**"],
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    rules: {
      // Recognizes this codebase's existing convention for a deliberately-unused parameter
      // (`_next`, `_args`, `_rest` already appear this way pre-lint) -- an *unprefixed* unused
      // var/arg/catch binding still flags normally.
      "@typescript-eslint/no-unused-vars": [
        "error",
        { argsIgnorePattern: "^_", varsIgnorePattern: "^_", caughtErrorsIgnorePattern: "^_" },
      ],
    },
  }
);
