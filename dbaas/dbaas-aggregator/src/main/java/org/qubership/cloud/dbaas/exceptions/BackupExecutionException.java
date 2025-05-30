package org.qubership.cloud.dbaas.exceptions;

import org.qubership.cloud.core.error.runtime.ErrorCodeException;
import lombok.Getter;

import java.net.URI;

@Getter
public class BackupExecutionException extends ErrorCodeException {
	private final URI location;
		public BackupExecutionException(URI location, String details, Throwable cause) {
			super(ErrorCodes.CORE_DBAAS_4018, ErrorCodes.CORE_DBAAS_4018.getDetail(details), cause);
			this.location = location;
		}
}
