import json
import logging
import os


logger = logging.getLogger(__name__)
logger.setLevel(logging.INFO)


def handler(event, context):
    if not isinstance(event, dict):
        raise ValueError("Expected a JSON object event")

    is_alb = "elb" in event.get("requestContext", {})
    source = "alb" if is_alb else event.get("source", "manual")
    result = {
        "message": "Test Lambda invoked successfully",
        "eventSource": source,
        "environment": os.environ.get("ENV_NAME", "local"),
        "requestId": context.aws_request_id,
    }
    logger.info("Handled event source=%s requestId=%s", source, context.aws_request_id)

    if is_alb:
        return {
            "statusCode": 200,
            "statusDescription": "200 OK",
            "isBase64Encoded": False,
            "headers": {"content-type": "application/json"},
            "body": json.dumps(result),
        }
    return result
