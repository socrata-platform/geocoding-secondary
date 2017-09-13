package com.socrata.geocodingsecondary

case class ComputationErrorException(reason: String, cause: Option[Exception] = None) extends Exception(reason, cause.orNull)
case class FatalComputationErrorException(reason: String, cause: Option[Exception] = None) extends Exception(reason, cause.orNull)
