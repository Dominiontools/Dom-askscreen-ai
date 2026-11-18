import { createRequire } from "node:module";
import { withLambda } from "@netlify/aws-lambda-compat";

const require = createRequire(import.meta.url);
const { handler } = require("../legacy-handlers/admin-dashboard.js");

export default withLambda(handler);
