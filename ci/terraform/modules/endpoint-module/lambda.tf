resource "aws_lambda_function" "endpoint_lambda" {
  filename      = var.lambda_zip_file
  function_name = replace("${var.environment}-${var.endpoint_name}-lambda", ".", "")
  role          = var.lambda_role_arn
  handler       = var.handler_function_name
  timeout       = 30
  memory_size   = 512

  tracing_config {
    mode = "Active"
  }

  source_code_hash = filebase64sha256(var.lambda_zip_file)
  vpc_config {
    security_group_ids = [var.security_group_id]
    subnet_ids = var.subnet_id
  }
  environment {
    variables = var.handler_environment_variables
  }

  runtime = var.handler_runtime

  tags = {
    environment = var.environment
  }
}

resource "aws_cloudwatch_log_subscription_filter" "log_subscription" {
  count           = var.logging_endpoint_enabled ? 1 : 0
  name            = "${var.endpoint_name}-log-subscription"
  log_group_name  = "/aws/lambda/${aws_lambda_function.endpoint_lambda.function_name}"
  filter_pattern  = ""
  destination_arn = var.logging_endpoint_arn
}
