package com.netcracker.cloud.dbaas.exceptions;

import com.netcracker.cloud.core.error.runtime.ErrorCodeException;

public class AdapterUnavailableException extends ErrorCodeException {
		public AdapterUnavailableException(int status) {
			super(ErrorCodes.CORE_DBAAS_4017, ErrorCodes.CORE_DBAAS_4017.getDetail(status));
		}
}
