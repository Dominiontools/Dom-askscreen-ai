import { createRequire } from "node:module";
import { withLambda } from "@netlify/aws-lambda-compat";

const require = createRequire(import.meta.url);
const { handler } = require("../legacy-handlers/crypto-payments.js");

export default withLambda(handler);
