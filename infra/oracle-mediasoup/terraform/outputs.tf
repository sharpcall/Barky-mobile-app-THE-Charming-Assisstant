output "public_ip" {
  description = "Ephemeral public IPv4 on the instance VNIC"
  value       = data.oci_core_vnic.instance.public_ip_address
}

output "private_ip" {
  value = data.oci_core_vnic.instance.private_ip_address
}

output "instance_id" {
  value = oci_core_instance.media.id
}

output "ssh_hint" {
  description = "SSH command template"
  value       = "ssh -i <ssh_private_key_path> ubuntu@${data.oci_core_vnic.instance.public_ip_address}"
}

output "stun_urls" {
  description = "ICE STUN URLs (coturn on same VM when enable_coturn=true)"
  value = [
    "stun:${data.oci_core_vnic.instance.public_ip_address}:3478",
  ]
}

output "turn_urls" {
  description = "ICE TURN URLs (username/password from secrets - not printed here)"
  value = [
    "turn:${data.oci_core_vnic.instance.public_ip_address}:3478?transport=udp",
    "turn:${data.oci_core_vnic.instance.public_ip_address}:3478?transport=tcp",
  ]
}

output "turn_realm" {
  value = var.turn_realm
}

output "mediasoup_signaling_url" {
  description = "HTTP/WS signaling base (TLS not enabled by default)"
  value       = "http://${data.oci_core_vnic.instance.public_ip_address}:${var.mediasoup_signaling_port}"
}

output "mediasoup_rtc_port_range" {
  value = "${var.mediasoup_rtc_min_port}-${var.mediasoup_rtc_max_port}"
}

output "coturn_relay_port_range" {
  value = "${var.relay_min_port}-${var.relay_max_port}"
}

output "nsg_id" {
  value = oci_core_network_security_group.media.id
}

output "shape" {
  value = "${var.instance_shape} ${var.ocpu_count} OCPU / ${var.memory_in_gbs} GB"
}

output "stack_note" {
  value = "Independent of infra/oracle-coturn (separate state). Free A1 quota is shared - prefer this one-box stack OR pure coturn, not both at full size."
}
