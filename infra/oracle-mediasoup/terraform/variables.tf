variable "tenancy_ocid" {
  type        = string
  description = "OCI tenancy OCID"
}

variable "user_ocid" {
  type        = string
  description = "OCI user OCID for API key"
}

variable "fingerprint" {
  type        = string
  description = "API key fingerprint"
}

variable "private_key_path" {
  type        = string
  description = "Path to OCI API private key PEM"
}

variable "region" {
  type        = string
  description = "OCI region (e.g. us-ashburn-1)"
}

variable "compartment_ocid" {
  type        = string
  description = "Compartment OCID (root tenancy OK for solo free tier)"
}

variable "ssh_public_key" {
  type        = string
  description = "SSH public key material for ubuntu user"
  sensitive   = true
}

variable "ssh_private_key_path" {
  type        = string
  description = "Local path to SSH private key (bootstrap scripts only; not required by pure terraform apply)"
  default     = ""
  sensitive   = true
}

variable "ssh_ingress_cidr" {
  type        = string
  description = "CIDR allowed to SSH (restrict to your IP when possible)"
  default     = "0.0.0.0/0"
}

variable "instance_shape" {
  type        = string
  description = "Compute shape. Free/paid A1: VM.Standard.A1.Flex. Paid AMD fallback on trial credits: VM.Standard.E4.Flex"
  default     = "VM.Standard.A1.Flex"
}

variable "ocpu_count" {
  type        = number
  description = "Flex OCPUs - Always Free A1 max is typically 2 (new free accounts mid-2026)"
  default     = 2
}

variable "memory_in_gbs" {
  type        = number
  description = "Flex memory GB - Always Free A1 max is typically 12"
  default     = 12
}

variable "boot_volume_size_in_gbs" {
  type        = number
  description = "Boot volume size (Always Free block volume allowance applies)"
  default     = 50
}

variable "availability_domain" {
  type        = string
  description = "Optional AD name; empty = first AD in region"
  default     = ""
}

variable "project_name" {
  type        = string
  description = "Name prefix for resources (independent of oracle-coturn stack)"
  default     = "barky-mediasoup"
}

# Separate VCN from oracle-coturn (10.42.0.0/16) so both stacks can coexist in theory.
# Note: Always Free A1 quota is shared - only one 2 OCPU / 12 GB box fits free tier.
variable "vcn_cidr" {
  type    = string
  default = "10.43.0.0/16"
}

variable "subnet_cidr" {
  type    = string
  default = "10.43.1.0/24"
}

# --- coturn (optional on same VM; free-tier simplicity) ---

variable "turn_realm" {
  type    = string
  default = "barky.local"
}

variable "turn_user" {
  type      = string
  default   = "barky"
  sensitive = true
}

variable "turn_password" {
  type        = string
  description = "Long-term TURN password"
  sensitive   = true
}

variable "turn_auth_secret" {
  type        = string
  description = "Optional static-auth-secret; empty disables REST secret auth"
  default     = ""
  sensitive   = true
}

# Narrower than pure coturn stack (49152-65535) so NSG stays small on shared free VM.
# Coturn min/max-port in cloud-init matches these.
variable "relay_min_port" {
  type    = number
  default = 49152
}

variable "relay_max_port" {
  type    = number
  default = 49200
}

# --- mediasoup / SFU signaling + RTC ---

variable "mediasoup_signaling_port" {
  type        = number
  description = "TCP port for mediasoup signaling (HTTP/WS)"
  default     = 3000
}

variable "mediasoup_rtc_min_port" {
  type        = number
  description = "UDP min for mediasoup WebRTC transports (keep NSG in sync)"
  default     = 40000
}

variable "mediasoup_rtc_max_port" {
  type        = number
  description = "UDP max for mediasoup WebRTC transports (keep NSG in sync)"
  default     = 40100
}

variable "enable_coturn" {
  type        = bool
  description = "Install and enable coturn on the same VM (recommended free-tier one-box)"
  default     = true
}
