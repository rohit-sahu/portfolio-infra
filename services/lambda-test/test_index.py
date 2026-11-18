import json
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from index import handler


class HandlerTest(unittest.TestCase):
    def setUp(self):
        self.context = SimpleNamespace(aws_request_id="test-request")

    def test_dummy_event(self):
        with patch.dict("os.environ", {"ENV_NAME": "dev"}):
            result = handler({"message": "hello"}, self.context)
        self.assertEqual(result["eventSource"], "manual")
        self.assertEqual(result["environment"], "dev")
        self.assertEqual(result["requestId"], "test-request")

    def test_s3_and_ec2_events(self):
        for source in ("aws.s3", "aws.ec2"):
            with self.subTest(source=source):
                result = handler({"source": source, "detail": {}}, self.context)
                self.assertEqual(result["eventSource"], source)

    def test_alb_response(self):
        result = handler(
            {"requestContext": {"elb": {"targetGroupArn": "test"}}, "httpMethod": "GET"},
            self.context,
        )
        self.assertEqual(result["statusCode"], 200)
        self.assertEqual(result["statusDescription"], "200 OK")
        self.assertFalse(result["isBase64Encoded"])
        self.assertEqual(result["headers"], {"content-type": "application/json"})
        self.assertEqual(json.loads(result["body"])["eventSource"], "alb")

    def test_invalid_event_fails(self):
        with self.assertRaisesRegex(ValueError, "JSON object"):
            handler([], self.context)


if __name__ == "__main__":
    unittest.main()
